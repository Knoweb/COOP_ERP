import { describe, expect, it } from "vitest";

// The rule for every screen (AGENTS.md): every user-visible string is a message id with en, si and
// ta texts. This test reads every source file of web/src/modules, web/src/shell and web/src itself
// (App.tsx, router.tsx) and fails on
//   - a text between JSX tags, or before an expression, that holds a letter of any script
//     ("Failed to remove image", "no items", "total {n}", Sinhala or Tamil typed in), and
//   - an aria-label, aria-description, alt, title, placeholder or label written as a string
//     literal (double or single quotes, or a template literal without an expression), and
//   - a browser alert(), confirm() or prompt(), window.alert() included, which cannot be
//     translated or styled.
// Like moduleStyle.test.ts it is a plain text search; it knows nothing about TypeScript, so it
// is cheap and predictable. If it stops you for a text that is not user-visible, rename or move
// it; if it really is not shown to a person, add it to ALLOWED below WITH A REASON.

type Allowed = { file: string; text: string; reason: string };

const ALLOWED: Allowed[] = [
  // A product or party name is data that came from the server, not a string of ours; none yet.
  { file: "shell/design/DesignPage.tsx", text: "MoneyDisplay", reason: "Component name shown as literal code in the design gallery, not natural language." },
  { file: "shell/design/DesignPage.tsx", text: "StateChip", reason: "Component name shown as literal code in the design gallery, not natural language." },
  { file: "shell/design/DesignPage.tsx", text: "TrainingBadge", reason: "Component name shown as literal code in the design gallery, not natural language." },
  { file: "shell/i18n/LangFallbackTag.tsx", text: "EN", reason: "The language code itself is untranslated by definition, indicating English fallback." },
  { file: "modules/m9integration/NotificationsPage.tsx", text: "[EN]", reason: "The language code of a template body shown in English as the fallback, untranslated by definition like LangFallbackTag." },
  { file: "router.tsx", text: "COOPFED ERP", reason: "The product's brand name in the sidebar, the same in every language." },
];

// A run of text after a tag's ">" that ends at the next tag or expression. Code also has ">"
// (generics, comparisons), so a run that reads like code is left out below: one with = ; " `
// ( ) | && or ??, one that starts with , : or ?, or one with no letter at all. Comments are
// blanked first (kept as spaces, so the line numbers hold). An HTML entity (&amp;) is text.
const JSX_TEXT = /(?<![=-])>([^<>{}]*?)(?=<|\{)/g;
const CODE_LIKE = /[=;"`()|]|&&|\?\?|^[,:?]/;
const ENTITY = /&#?\w+;/g;

/** The source with its comments turned into spaces; `//` right after a colon (a URL) is not a comment. */
function withoutComments(source: string): string {
  const blank = (text: string) => text.replace(/[^\n]/g, " ");
  return source.replace(/\/\*[\s\S]*?\*\//g, blank).replace(/(?<![:"'])\/\/[^\n]*/g, blank);
}
const LITERAL_ATTRIBUTE =
  /(?<![\w-])(aria-label|aria-description|alt|title|placeholder|label)=(?:"([^"{}]*)"|'([^'{}]*)'|\{\s*"([^"]*)"\s*\}|\{\s*'([^']*)'\s*\}|\{\s*`([^`$]*)`\s*\})/g;
const BROWSER_DIALOG = /(?<![\w.$])(?:(?:window|globalThis|self)\.)?(?:alert|confirm|prompt)\(/g;

function lineOf(source: string, index: number): number {
  return source.slice(0, index).split(/\r?\n/).length;
}

/** The user-visible literals of a source text: ["12: JSX text 'Failed to remove image'"]. */
export function literalsIn(source: string): { text: string; found: string }[] {
  const found: { text: string; found: string }[] = [];
  for (const match of withoutComments(source).matchAll(JSX_TEXT)) {
    const text = match[1].replace(/\s+/g, " ").trim();
    if (!/\p{L}/u.test(text) || CODE_LIKE.test(text.replace(ENTITY, " "))) {
      continue;
    }
    // No single-word check, we want to catch one-word literals like "Image".
    found.push({ text, found: `${lineOf(source, match.index ?? 0)}: JSX text "${text}"` });
  }
  for (const match of source.matchAll(LITERAL_ATTRIBUTE)) {
    const text = match[2] ?? match[3] ?? match[4] ?? match[5] ?? match[6] ?? "";
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
    expect(literalsIn(`<th>Image</th>`)).toHaveLength(1);
    expect(literalsIn(`<th>SKU</th>`)).toHaveLength(1);
    expect(literalsIn(`<p>\n  No image uploaded\n</p>`)).toHaveLength(1);
    expect(literalsIn(`<button aria-label="Notifications">`)).toHaveLength(1);
    expect(literalsIn(`<img alt="Thumbnail" />`)).toHaveLength(1);
    expect(literalsIn(`<input placeholder={"Search here"} />`)).toHaveLength(1);
    expect(literalsIn(`alert("Failed")`)).toHaveLength(1);
  });

  it("finds the shapes the first version missed (WCD-06)", () => {
    expect(literalsIn(`window.alert("x")`)).toHaveLength(1);
    expect(literalsIn(`window.confirm("Delete?")`)).toHaveLength(1);
    expect(literalsIn(`<p>total {n} items</p>`)).toHaveLength(1);
    expect(literalsIn(`<p>Total {n}</p>`)).toHaveLength(1);
    expect(literalsIn(`<span>save &amp; close</span>`)).toHaveLength(1);
    expect(literalsIn(`<p>no items</p>`)).toHaveLength(1);
    expect(literalsIn(`<p>සමිතිය</p>`)).toHaveLength(1);
    expect(literalsIn(`<p>சங்கம்</p>`)).toHaveLength(1);
    expect(literalsIn(`<img alt='Logo'/>`)).toHaveLength(1);
    expect(literalsIn("<img alt={`Logo`}/>")).toHaveLength(1);
    expect(literalsIn(`<x label="Hello"/>`)).toHaveLength(1);
    expect(literalsIn(`<x aria-description="More about this"/>`)).toHaveLength(1);
  });

  it("accepts message ids, expressions, empty alt text and code", () => {
    expect(literalsIn(`<p>{t("pricing.saved").text}</p>`)).toEqual([]);
    expect(literalsIn(`<img alt="" aria-hidden="true" />`)).toEqual([]);
    expect(literalsIn(`<button aria-label={t("shell.user.notifications").text}>`)).toEqual([]);
    expect(literalsIn(`const rows: Array<Row> = [];`)).toEqual([]);
    expect(literalsIn(`const call = (k: string) => Promise<unknown>;`)).toEqual([]);
    expect(literalsIn(`window.alert.bind(x); const prompts = 1;`)).toEqual([]);
    expect(literalsIn(`toast.confirm(x); dialog.prompt(y);`)).toEqual([]);
    expect(literalsIn("<img alt={`${name}`} />")).toEqual([]);
    expect(literalsIn(`<p>{n} {t("trading.items").text}</p>`)).toEqual([]);
    expect(literalsIn(`if (a > b && c < d) { return; }`)).toEqual([]);
    expect(literalsIn(`const m = new Map<string, number>();\nconst x = { a: 1 };`)).toEqual([]);
    expect(literalsIn(`const f = (x: number): Promise<void> => { return; };`)).toEqual([]);
  });
});

describe("the web modules and the shell", () => {
  const sources = import.meta.glob(
    ["../*.tsx", "../modules/**/*.tsx", "../shell/**/*.tsx", "!../*.test.tsx", "!../modules/**/*.test.tsx", "!../shell/**/*.test.tsx"],
    { query: "?raw", import: "default", eager: true }
  ) as Record<string, string>;

  it("are found by this test (a wrong path would make it pass for ever)", () => {
    expect(Object.keys(sources)).toContain("../modules/m2catalogue/SkuPage.tsx");
    expect(Object.keys(sources)).toContain("../shell/auth/UserMenu.tsx");
    expect(Object.keys(sources)).toContain("../router.tsx");
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
