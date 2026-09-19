import { describe, expect, it } from "vitest";
import { messages } from "./messages";

// The backend catalogues are checked by tools/check-i18n.mjs. These are the web client's own
// catalogues (the shell's ids plus every module's <module>.messages.json), and nothing else
// checks them. A missing Sinhala or Tamil text does not break the screen: it silently shows
// English. So the build has to notice, not the user.
describe("web message catalogues", () => {
  const languages = Object.keys(messages) as Array<keyof typeof messages>;
  const allIds = [...new Set(languages.flatMap((language) => Object.keys(messages[language])))].sort();

  it("exist for English, Sinhala and Tamil", () => {
    expect(languages.sort()).toEqual(["en", "si", "ta"]);
  });

  it("have every message id in all three languages", () => {
    const missing = allIds.flatMap((id) =>
      languages.filter((language) => !(id in messages[language])).map((language) => `${language}: ${id}`)
    );
    expect(missing).toEqual([]);
  });

  it("have no empty text", () => {
    const empty = languages.flatMap((language) =>
      Object.entries(messages[language])
        .filter(([, text]) => text.trim() === "")
        .map(([id]) => `${language}: ${id}`)
    );
    expect(empty).toEqual([]);
  });

  it("keep ICU placeholders the same in every language", () => {
    // "{count} items" must not become "items" in a translation: the value would vanish.
    const placeholders = (text: string) => [...text.matchAll(/\{(\w+)/g)].map((match) => match[1]).sort();
    const different = allIds.flatMap((id) =>
      languages
        .filter((language) => id in messages[language] && id in messages.en)
        .filter((language) => placeholders(messages[language][id]).join() !== placeholders(messages.en[id]).join())
        .map((language) => `${language}: ${id}`)
    );
    expect(different).toEqual([]);
  });
});
