import { describe, expect, it } from "vitest";
import { initialsOf } from "./initials";

describe("initialsOf", () => {
  it("takes the first letter of the first two words, upper-cased", () => {
    expect(initialsOf("nimal perera")).toBe("NP");
    expect(initialsOf("  Amara  Kumari  Silva ")).toBe("AK");
  });

  it("gives one letter for one word and nothing for nothing", () => {
    expect(initialsOf("Kamal")).toBe("K");
    expect(initialsOf("   ")).toBe("");
    expect(initialsOf("")).toBe("");
  });

  it("keeps a Sinhala consonant with its vowel sign (a grapheme, not a code point)", () => {
    // කු = ක + ු ; the code point alone would show ක
    expect(initialsOf("කුමාර පෙරේරා")).toBe("කුපෙ");
  });

  it("keeps a Tamil consonant with its vowel sign", () => {
    // கு = க + ு
    expect(initialsOf("குமார் சிவா")).toBe("குசி");
  });
});
