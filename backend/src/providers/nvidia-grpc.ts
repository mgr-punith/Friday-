import { connect, constants, type ClientHttp2Session, type ClientHttp2Stream, type IncomingHttpHeaders } from "node:http2";
import { TranscriptionError, type TranscriptionOptions } from "./transcription";

const ORIGIN = "https://grpc.nvcf.nvidia.com";
const RECOGNIZE_PATH = "/nvidia.riva.asr.RivaSpeechRecognition/Recognize";
const MAX_RESPONSE = 65_536;
export type GrpcConnect = (origin: string) => ClientHttp2Session;
export interface RivaUnaryClient { recognize(message: Uint8Array, options: TranscriptionOptions): Promise<Uint8Array> }

/** One bounded unary gRPC call on a reused TLS HTTP/2 session. Never log headers or provider bodies. */
export class NvidiaGrpcClient implements RivaUnaryClient {
  private session: ClientHttp2Session | undefined;
  constructor(private readonly apiKey: string, private readonly functionId: string,
    private readonly connector: GrpcConnect = origin => connect(origin)) {}

  recognize(message: Uint8Array, options: TranscriptionOptions): Promise<Uint8Array> {
    if (options.signal.aborted) return Promise.reject(new TranscriptionError("timeout"));
    if (message.byteLength > 1_001_024 || !Number.isFinite(options.timeoutMs) || options.timeoutMs < 1 || options.timeoutMs > 25_000) {
      return Promise.reject(new TranscriptionError("bad_audio"));
    }
    return new Promise((resolve, reject) => {
      let stream: ClientHttp2Stream | undefined;
      let done = false; let length = 0;
      let status: string | undefined;
      const chunks: Buffer[] = [];
      const finish = (failure?: TranscriptionError, result?: Uint8Array) => {
        if (done) return;
        done = true; clearTimeout(timer); options.signal.removeEventListener("abort", abort);
        chunks.length = 0;
        if (failure) { try { stream?.close(constants.NGHTTP2_CANCEL); } catch { try { stream?.destroy(); } catch {} } reject(failure); } else resolve(result!);
      };
      const abort = () => finish(new TranscriptionError("timeout"));
      const timer = setTimeout(abort, options.timeoutMs);
      options.signal.addEventListener("abort", abort, { once: true });
      if (options.signal.aborted) { abort(); return; }
      const readStatus = (headers: IncomingHttpHeaders) => {
        if (done) return;
        const value = headers["grpc-status"];
        if (value === undefined) return;
        if (typeof value !== "string" || !/^(?:[0-9]|1[0-6])$/.test(value) || status !== undefined) {
          finish(new TranscriptionError("invalid_response")); return;
        }
        status = value;
        if (value !== "0") {
          const reason = value === "4" ? "timeout" : value === "8" ? "rate_limited"
            : value === "7" || value === "16" ? "unauthorized" : "unavailable";
          finish(new TranscriptionError(reason, value === "14"));
        }
      };
      try {
        stream = this.connection().request({ ":method": "POST", ":path": RECOGNIZE_PATH,
          "grpc-timeout": `${Math.ceil(options.timeoutMs)}m`, "content-type": "application/grpc+proto", te: "trailers",
          "grpc-accept-encoding": "identity", "function-id": this.functionId, authorization: `Bearer ${this.apiKey}` });
        stream.on("error", () => finish(new TranscriptionError("network_error", true)));
        stream.on("response", headers => {
          if (done) return;
          const httpStatus = headers[":status"];
          if (httpStatus !== 200) {
            finish(new TranscriptionError(httpStatus === 401 || httpStatus === 403 ? "unauthorized" : httpStatus === 429 ? "rate_limited" : "unavailable",
              httpStatus === 500 || httpStatus === 502 || httpStatus === 503 || httpStatus === 504)); return;
          }
          const contentType = headers["content-type"];
          if (typeof contentType !== "string" || !/^application\/grpc(?:\+proto)?(?:;|$)/i.test(contentType) ||
            headers["grpc-encoding"] !== undefined && headers["grpc-encoding"] !== "identity") {
            finish(new TranscriptionError("invalid_response")); return;
          }
          readStatus(headers);
        });
        stream.on("trailers", readStatus);
        stream.on("data", (chunk: Buffer) => {
          if (done) return;
          length += chunk.byteLength;
          if (length > MAX_RESPONSE + 5) { finish(new TranscriptionError("invalid_response")); return; }
          chunks.push(chunk);
        });
        stream.on("end", () => {
          if (done) return;
          const bytes = Buffer.concat(chunks, length);
          if (status !== "0" || bytes.length < 5 || bytes[0] !== 0 || bytes.readUInt32BE(1) !== bytes.length - 5) {
            finish(new TranscriptionError("invalid_response")); return;
          }
          finish(undefined, bytes.subarray(5));
        });
        stream.on("close", () => { if (!done) finish(new TranscriptionError("network_error", true)); });
        const prefix = Buffer.alloc(5); prefix.writeUInt32BE(message.byteLength, 1);
        stream.end(Buffer.concat([prefix, message]));
      } catch { finish(new TranscriptionError("network_error", true)); }
    });
  }

  close(): void { this.session?.destroy(); this.session = undefined; }

  private connection(): ClientHttp2Session {
    if (this.session && !this.session.closed && !this.session.destroyed) return this.session;
    const session = this.connector(ORIGIN);
    this.session = session;
    session.unref();
    session.on("error", () => { if (this.session === session) this.session = undefined; session.destroy(); });
    session.on("goaway", () => { if (this.session === session) this.session = undefined; session.close(); });
    session.on("close", () => { if (this.session === session) this.session = undefined; });
    return session;
  }
}
