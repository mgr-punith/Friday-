/** Match a complete request to leave a quiet mode, including a repeated final "off". */
export function turnsOffQuietMode(utterance: string): boolean {
  if (/[\u0000-\u001f\u007f-\u009f]/.test(utterance)) return false;
  const words = utterance.toLowerCase().replace(/[’']/g, "").replace(/[.!?]+$/, "").replace(/\s+/g, " ").trim()
    .replace(/^(?:(?:can|could|would) you )?(?:please )?/, "").replace(/,? please$/, "");
  const target = "(?:(?:the|my) )?(?:phones? )?(?:vibrate|vibration|silent)(?: mode)?";
  return new RegExp(`^(?:turn|switch) off ${target}(?: off)?$`).test(words) ||
    new RegExp(`^(?:turn|switch|set|put) ${target} off$`).test(words) ||
    new RegExp(`^disable ${target}$`).test(words) || /^unmute(?: (?:(?:the|my) )?phone)?$/.test(words);
}
