export function isFridayWav(bytes: Uint8Array): boolean {
  const buffer = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (buffer.length < 44) return false;
  if (buffer.toString("ascii", 0, 4) !== "RIFF" || buffer.toString("ascii", 8, 12) !== "WAVE") {
    return false;
  }

  const declaredSize = buffer.readUInt32LE(4) + 8;
  if (declaredSize !== buffer.length || declaredSize < 44) return false;

  let format: WavFormat | undefined;
  let hasAudio = false;
  let offset = 12;
  while (offset + 8 <= declaredSize) {
    const chunkName = buffer.toString("ascii", offset, offset + 4);
    const chunkSize = buffer.readUInt32LE(offset + 4);
    const chunkStart = offset + 8;
    const chunkEnd = chunkStart + chunkSize;
    if (chunkEnd > declaredSize) return false;

    if (chunkName === "fmt " && chunkSize >= 16) {
      format = {
        encoding: buffer.readUInt16LE(chunkStart),
        channels: buffer.readUInt16LE(chunkStart + 2),
        sampleRate: buffer.readUInt32LE(chunkStart + 4),
        bitsPerSample: buffer.readUInt16LE(chunkStart + 14)
      };
    } else if (chunkName === "data" && chunkSize > 0) {
      hasAudio = true;
    }

    offset = chunkEnd + (chunkSize % 2);
  }

  return (
    hasAudio &&
    format?.encoding === 1 &&
    format.channels === 1 &&
    format.sampleRate === 16_000 &&
    format.bitsPerSample === 16
  );
}

interface WavFormat {
  readonly encoding: number;
  readonly channels: number;
  readonly sampleRate: number;
  readonly bitsPerSample: number;
}
