import type { FunctionUrlEvent, FunctionUrlResponse } from "../core/api";

export interface FridayApi {
  handle(event: FunctionUrlEvent): Promise<FunctionUrlResponse>;
}

export function createVercelHandler(api: FridayApi) {
  return async function handle(request: Request): Promise<Response> {
    const event = await toFunctionEvent(request);
    const response = await api.handle(event);
    const init: ResponseInit = {
      status: response.statusCode,
      ...(response.headers ? { headers: response.headers } : {})
    };
    return new Response(response.body === undefined ? null
      : response.isBase64Encoded ? Buffer.from(response.body, "base64") : response.body, init);
  };
}

async function toFunctionEvent(request: Request): Promise<FunctionUrlEvent> {
  const url = new URL(request.url);
  const route = url.searchParams.get("fridayRoute");
  const rawPath = route?.startsWith("auth/") && /^[a-z/]+$/.test(route) ? `/v1/${route}` : route === "register" || route === "warm" || route === "command" || route === "answers/report" || route === "speech"
    ? `/v1/${route}`
    : url.pathname;
  const headers = Object.fromEntries(request.headers.entries());
  const contentType = request.headers.get("content-type")?.split(";", 1)[0]?.trim().toLowerCase();
  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  const sourceIp = clientIp(request.headers);
  const requestId = request.headers.get("x-vercel-id") ?? undefined;
  let body: string | undefined;
  let isBase64Encoded = false;

  if (hasBody && rawPath === "/v1/speech") {
    body = await readSpeechBody(request);
  } else if (hasBody && contentType === "audio/wav") {
    body = Buffer.from(await request.arrayBuffer()).toString("base64");
    isBase64Encoded = true;
  } else if (hasBody) {
    body = await request.text();
  }

  return {
    rawPath,
    rawQueryString: url.searchParams.toString(),
    headers,
    ...(body === undefined ? {} : { body }),
    isBase64Encoded,
    requestContext: {
      ...(requestId ? { requestId } : {}),
      http: {
        method: request.method,
        ...(sourceIp ? { sourceIp } : {})
      }
    }
  };
}

async function readSpeechBody(request: Request): Promise<string | undefined> {
  const maximum = 16_384;
  if (!request.body) return undefined;
  if (Number(request.headers.get("content-length")) > maximum) {
    void request.body.cancel().catch(() => {});
    return undefined;
  }
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  let complete = false;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) { complete = true; break; }
      length += next.value.byteLength;
      if (length > maximum) return undefined;
      chunks.push(next.value);
    }
    return Buffer.concat(chunks).toString("utf8");
  } finally {
    if (!complete) void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

function clientIp(headers: Headers): string | undefined {
  const forwarded = headers.get("x-vercel-forwarded-for") ?? headers.get("x-forwarded-for");
  return forwarded?.split(",", 1)[0]?.trim() || undefined;
}
