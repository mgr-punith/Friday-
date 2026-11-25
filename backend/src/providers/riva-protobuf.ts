import { TranscriptionError } from "./transcription";

/** Narrow wire codec for NVIDIA's published Riva Recognize RPC; no general-purpose protobuf runtime.
 * https://github.com/nvidia-riva/common/blob/main/riva/proto/riva_asr.proto
 */
export function encodeRivaRecognize(pcm: Uint8Array): Buffer {
  const config = Buffer.concat([integer(1, 1), integer(2, 16_000), bytes(3, Buffer.from("en-US")),
    integer(4, 1), integer(7, 1), integer(8, 1), integer(11, 1)]);
  return Buffer.concat([bytes(1, config), bytes(2, pcm)]);
}

export interface RivaWord { readonly startMs: number; readonly endMs: number; readonly word: string }
export interface RivaAlternative {
  readonly transcript: string;
  readonly confidence?: number;
  readonly words: readonly RivaWord[];
  readonly languages: readonly string[];
}
export interface RivaResult { readonly alternatives: readonly RivaAlternative[] }

export function decodeRivaRecognize(value: Uint8Array): readonly RivaResult[] {
  if (value.byteLength > 65_536) throw invalid();
  const results: RivaResult[] = [];
  for (const field of fields(value)) if (field.id === 1) {
    if (results.length >= 10) throw invalid();
    const alternatives: RivaAlternative[] = [];
    for (const alternative of fields(message(field))) if (alternative.id === 1) {
      if (alternatives.length >= 1) throw invalid();
      alternatives.push(decodeAlternative(message(alternative)));
    }
    results.push({ alternatives });
  }
  return results;
}

function decodeAlternative(value: Uint8Array): RivaAlternative {
  let transcript: string | undefined;
  let confidence: number | undefined;
  const words: RivaWord[] = [];
  const languages: string[] = [];
  for (const field of fields(value)) {
    if (field.id === 1) {
      if (transcript !== undefined) throw invalid();
      transcript = string(field);
    } else if (field.id === 2) {
      if (confidence !== undefined || field.wire !== 5) throw invalid();
      confidence = Buffer.from(field.data).readFloatLE();
      if (!Number.isFinite(confidence)) throw invalid();
    } else if (field.id === 3) {
      if (words.length >= 200) throw invalid();
      words.push(decodeWord(message(field)));
    } else if (field.id === 4) {
      if (languages.length >= 2) throw invalid();
      languages.push(string(field));
    }
  }
  return { transcript: transcript ?? "", words, languages, ...(confidence === undefined ? {} : { confidence }) };
}

function decodeWord(value: Uint8Array): RivaWord {
  let startMs = 0; let endMs = 0; let word = "";
  const seen = new Set<number>();
  for (const field of fields(value)) {
    if (field.id >= 1 && field.id <= 3) {
      if (seen.has(field.id)) throw invalid();
      seen.add(field.id);
    }
    if (field.id === 1 || field.id === 2) {
      if (field.wire !== 0 || field.number === undefined || field.number > 2_147_483_647n) throw invalid();
      if (field.id === 1) startMs = Number(field.number); else endMs = Number(field.number);
    } else if (field.id === 3) word = string(field);
  }
  return { startMs, endMs, word };
}

interface Field { readonly id: number; readonly wire: number; readonly data: Uint8Array; readonly number?: bigint }
function* fields(value: Uint8Array): Generator<Field> {
  let offset = 0; let count = 0;
  const varint = (): bigint => {
    let result = 0n;
    for (let index = 0; index < 10; index++) {
      if (offset >= value.byteLength) throw invalid();
      const byte = value[offset++]!;
      if (index === 9 && byte > 1) throw invalid();
      result |= BigInt(byte & 127) << BigInt(index * 7);
      if ((byte & 128) === 0) return result;
    }
    throw invalid();
  };
  while (offset < value.byteLength) {
    if (++count > 1_000) throw invalid();
    const tag = varint();
    if (tag > 0xffff_ffffn) throw invalid();
    const id = Number(tag >> 3n); const wire = Number(tag & 7n);
    if (id === 0) throw invalid();
    if (wire === 0) { yield { id, wire, data: new Uint8Array(0), number: varint() }; continue; }
    const length = wire === 1 ? 8 : wire === 5 ? 4 : wire === 2 ? Number(varint()) : -1;
    if (!Number.isSafeInteger(length) || length < 0 || length > value.byteLength - offset) throw invalid();
    yield { id, wire, data: value.subarray(offset, offset + length) };
    offset += length;
  }
}
function message(field: Field): Uint8Array { if (field.wire !== 2) throw invalid(); return field.data; }
function string(field: Field): string {
  try { return new TextDecoder("utf-8", { fatal: true }).decode(message(field)); }
  catch { throw invalid(); }
}
function varint(value: number): Buffer {
  const result: number[] = [];
  do { const byte = value % 128; value = Math.floor(value / 128); result.push(byte | (value > 0 ? 128 : 0)); } while (value > 0);
  return Buffer.from(result);
}
function integer(field: number, value: number): Buffer { return Buffer.concat([varint(field * 8), varint(value)]); }
function bytes(field: number, value: Uint8Array): Buffer { return Buffer.concat([varint(field * 8 + 2), varint(value.byteLength), value]); }
function invalid(): TranscriptionError { return new TranscriptionError("invalid_response"); }
