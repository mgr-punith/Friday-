/** Bound both streamed audio and credential responses under the caller's deadline. */
export async function readSpeechBytes(response: Response, maximum: number, signal: AbortSignal): Promise<Buffer> {
  if (!response.body) throw new Error("speech_empty_response");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  let complete = false;
  try {
    while (true) {
      const next = await abortable(reader.read(), signal);
      if (next.done) break;
      length += next.value.byteLength;
      if (length > maximum) throw new Error("speech_large_response");
      chunks.push(next.value);
    }
    complete = true;
  } finally {
    if (!complete) void reader.cancel().catch(() => {});
    reader.releaseLock();
  }
  signal.throwIfAborted();
  return Buffer.concat(chunks, length);
}

export function abortable<T>(operation: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(new Error("speech_timeout"));
    if (signal.aborted) { operation.catch(() => {}); abort(); return; }
    signal.addEventListener("abort", abort, { once: true });
    operation.then(value => { signal.removeEventListener("abort", abort); resolve(value); }, error => {
      signal.removeEventListener("abort", abort); reject(error);
    });
  });
}
