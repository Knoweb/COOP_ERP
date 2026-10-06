import { describe, expect, it } from "vitest";

// The rule for every screen (AGENTS.md): every user-visible string is a message id with en, si and
// ta texts. This test reads every source file of web/src/modules and web/src/shell and fails on
//   - a text between JSX tags that starts with a capital letter and holds a space ("Failed to
//     remove image", "No image uploaded"), and
//   - an aria-label, alt, title or placeholder written as a string literal, and
//   - a browser alert(), confirm() or prompt(), which cannot be translated or styled.
// Like moduleStyle.test.ts it is a plain text search; it knows nothing about TypeScript, so it
// is cheap and predictable. If it stops you for a text that is not user-visible, rename or move
// it; if it really is not shown to a person, add it to ALLOWED below WITH A REASON.

type Allowed = { file: string; text: string; reason: string };

const ALLOWED: Allowed[] = [
  // A product or party name is data that came from the server, not a string of ours; none yet.
];

const JSX_TEXT = />\s*([A-Z][^<>{}=;"`]*\s[^<>{}=;"`]*?)\s*</g;
const LITERAL_ATTRIBUTE = /\b(aria-label|alt|title|placeholder)=(?:"([^"{}]*)"|\{\s*"([^"]*)"\s*\})/g;
const BROWSER_DIALOG = /(?<![\w.])(?:alert|confirm|prompt)\(/g;

function lineOf(source: string, index: number): number {
  return source.slice(0, index).split(/\r?\n/).length;
}

/** The user-visible literals of a source text: ["12: JSX text 'Failed to remove image'"]. */
export function literalsIn(source: string): { text: string; found: string }[] {
  const found: { text: string; found: string }[] = [];
  for (const match of source.matchAll(JSX_TEXT)) {
    const text = match[1].replace(/\s+/g, " ").trim();
    if (!/\s/.test(text)) continue; // one word ("SKU", "EN") is a code, not a sentence
    found.push({ text, found: `${lineOf(source, match.index ?? 0)}: JSX text "${text}"` });
  }
  for (const match of source.matchAll(LITERAL_ATTRIBUTE)) {
    const text = match[2] ?? match[3] ?? "";
    if (/\p{L}/u.test(text)) {
      found.push({ text, found: `${lineOf(source, match.index ?? 0)}: ${match[1]} "${text}"` });
    }
  }
  for (const match of source.matchAll(BROWSER_DIALOG)) {
    found.push({ text: match[0], found: `${lineOf(source, match.index ?? 0)}: browser dialog ${match[0]}` });
  }
  return found;
}

describe("the search for user-visible literals", () => {
  it("finds JSX text, a literal label and a browser alert", () => {
    expect(literalsIn(`<p>Failed to remove image</p>`)).toHaveLength(1);
    expect(literalsIn(`<p>\n  No image uploaded\n</p>`)).toHaveLength(1);
    expect(literalsIn(`<button aria-label="Notifications">`)).toHaveLength(1);
    expect(literalsIn(`<img alt="Thumbnail" />`)).toHaveLength(1);
    expect(literalsIn(`<input placeholder={"Search here"} />`)).toHaveLength(1);
    expect(literalsIn(`alert("Failed")`)).toHaveLength(1);
  });

  it("accepts message ids, one-word texts, expressions, empty alt text and code", () => {
    expect(literalsIn(`<p>{t("pricing.saved").text}</p>`)).toEqual([]);
    expect(literalsIn(`<th>SKU</th>`)).toEqual([]);
    expect(literalsIn(`<img alt="" aria-hidden="true" />`)).toEqual([]);
    expect(literalsIn(`<button aria-label={t("shell.user.notifications").text}>`)).toEqual([]);
    expect(literalsIn(`const rows: Array<Row> = [];`)).toEqual([]);
    expect(literalsIn(`window.alert.bind(x); const prompts = 1;`)).toEqual([]);
  });
});

describe("the web modules and the shell", () => {
  const sources = import.meta.glob(
    ["../modules/**/*.tsx", "../shell/**/*.tsx", "!../modules/**/*.test.tsx", "!../shell/**/*.test.tsx"],
    { query: "?raw", import: "default", eager: true }
  ) as Record<string, string>;

  it("are found by this test (a wrong path would make it pass for ever)", () => {
    expect(Object.keys(sources)).toContain("../modules/m2catalogue/SkuPage.tsx");
    expect(Object.keys(sources)).toContain("../shell/auth/UserMenu.tsx");
  });

  it("every allow-list entry has a reason and still matches something", () => {
    for (const entry of ALLOWED) {
      expect(entry.reason.trim().length, `${entry.file}: ${entry.text}`).toBeGreaterThan(10);
      const source = sources[`../${entry.file}`];
      expect(source, `${entry.file} is not a source file`).toBeDefined();
      expect(literalsIn(source).some((hit) => hit.text === entry.text), `${entry.file}: "${entry.text}" is gone; remove the entry`).toBe(true);
    }
  });

  it("write no user-visible text of their own: a message id in en, si and ta", () => {
    const found = Object.entries(sources).flatMap(([file, source]) => {
      const name = file.replace("../", "");
      return literalsIn(source)
        .filter((hit) => !ALLOWED.some((entry) => entry.file === name && entry.text === hit.text))
        .map((hit) => `web/src/${name}:${hit.found}`);
    });
    expect(found, "use t(\"<id>\") with the id in the module's messages.json (en, si, ta); AGENTS.md").toEqual([]);
  });
});
