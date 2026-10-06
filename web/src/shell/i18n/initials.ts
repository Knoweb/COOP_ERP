// The letters in a person's avatar circle, made here and nowhere else: a display name must never
// leave the browser for an image service (TWK-11).
//
// The first *grapheme* of the first two words, not the first code point: the first letter of a
// Sinhala or Tamil name is a consonant plus a vowel sign, and the consonant alone is a different
// letter.

type Segmenting = { segment(text: string): Iterable<{ segment: string }> };
const graphemes: Segmenting | null =
  typeof Intl !== "undefined" && "Segmenter" in Intl
    ? new (Intl as unknown as { Segmenter: new (l?: string, o?: object) => Segmenting }).Segmenter(undefined, { granularity: "grapheme" })
    : null;

function firstGrapheme(word: string): string {
  if (graphemes) {
    for (const part of graphemes.segment(word)) {
      return part.segment;
    }
    return "";
  }
  return Array.from(word)[0] ?? "";
}

/** "Nimal Perera" gives "NP"; one word gives one letter; nothing gives "". */
export function initialsOf(displayName: string): string {
  return displayName
    .trim()
    .split(/\s+/)
    .filter((word) => word !== "")
    .slice(0, 2)
    .map(firstGrapheme)
    .join("")
    .toLocaleUpperCase();
}
