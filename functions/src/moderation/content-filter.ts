/** A bounded baseline filter, not an assertion of exhaustive moderation.
 * Reports/evidence are deliberately not filtered. Probe normalization never
 * rewrites a member's stored text. Human moderation remains required. */
const severePatterns = [
  /\bkill\s+yourself\b/u,
  /\bjag\s+ska\s+d[öo]da\s+dig\b/u,
  /\b(?:barnporr|child\s+porn)\b/u,
  /\bn[i1]gg[e3]r(?:s)?\b/u,
];
export function containsObjectionableText(text: string): boolean {
  const probe = text
    .normalize('NFKC')
    .replace(/[\u200B-\u200D\uFEFF]/g, '')
    .toLocaleLowerCase('sv');
  return severePatterns.some((pattern) => pattern.test(probe));
}
