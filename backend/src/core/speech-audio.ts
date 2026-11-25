export const MAX_SPEECH_AUDIO_BYTES = 1_920_044;

/** Match the phone's bounded mono PCM16 parser, including padded metadata chunks. */
export function isSpeechWav(bytes: Uint8Array): boolean {
  if (bytes.byteLength < 46 || bytes.byteLength > MAX_SPEECH_AUDIO_BYTES) return false;
  const buffer = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (buffer.toString("ascii", 0, 4) !== "RIFF" || buffer.toString("ascii", 8, 12) !== "WAVE" ||
    buffer.readUInt32LE(4) + 8 !== buffer.length) return false;
  let format = false;
  let audio = false;
  let offset = 12;
  while (offset + 8 <= buffer.length) {
    const name = buffer.toString("ascii", offset, offset + 4);
    const length = buffer.readUInt32LE(offset + 4);
    const start = offset + 8;
    if (start + length > buffer.length) return false;
    if (name === "fmt ") {
      if (format || length < 16 || buffer.readUInt16LE(start) !== 1 || buffer.readUInt16LE(start + 2) !== 1 ||
        buffer.readUInt32LE(start + 4) !== 16_000 || buffer.readUInt32LE(start + 8) !== 32_000 ||
        buffer.readUInt16LE(start + 12) !== 2 || buffer.readUInt16LE(start + 14) !== 16) return false;
      format = true;
    } else if (name === "data") {
      if (audio || length === 0 || length % 2 !== 0 || length > 16_000 * 2 * 60) return false;
      audio = true;
    }
    offset = start + length + length % 2;
  }
  return format && audio && offset === buffer.length;
}
