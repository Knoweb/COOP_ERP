import { describe, expect, it } from "vitest";

// The rule for every module (hello/README.md, "The web module"): a module writes no colour and
// no length of its own. It uses the tokens of design/tokens.css, var(--space-2), and the shared
// components. This test reads every source file under web/src/modules and fails on a literal.
//
// Why a test and not a review remark: forty screens by fifteen people, and "#c00" looks harmless
// in a pull request. The contrast of the tokens is checked (tokens.test.ts); the contrast of a
// colour somebody typed into a screen is checked by nobody.
//
// It is a plain text search, line by line, and knows nothing about TypeScript. That keeps it
// cheap and predictable. It skips comment lines and test files; if it ever stops you for a text
// that is not a style (a document number "#1234" in a sample), move that text into the message
// catalogue, where user-visible text belongs anyway.

const COLOUR_LITERAL = /#[0-9a-fA-F]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\(/;
const LENGTH_LITERAL = /(?<![\w.-])\d*\.?\d+(?:px|rem|em|pt)\b/;

/** The lines of a source text that write a colour or a length as a literal: ["12: color: #b00020"]. */
function styleLiteralsIn(source: string): string[] {
  return source
    .split(/\r?\n/)
    .map((line, index) => ({ line: line.trim(), number: index + 1 }))
    .filter(({ line }) => !/^(\/\/|\/\*|\*|\{\/\*)/.test(line))
    .filter(({ line }) => COLOUR_LITERAL.test(line) || LENGTH_LITERAL.test(line))
    .map(({ line, number }) => `${number}: ${line}`);
}

describe("the search for style literals", () => {
  it("finds a hex colour, a colour function and a length in px, rem or em", () => {
    expect(styleLiteralsIn(`<span style={{ color: "#b00020" }}>`)).toHaveLength(1);
    expect(styleLiteralsIn(`background: rgba(0, 0, 0, 0.5);`)).toHaveLength(1);
    expect(styleLiteralsIn(`<main style={{ padding: "2rem" }}>`)).toHaveLength(1);
    expect(styleLiteralsIn(`border-bottom: 1px solid var(--color-border);`)).toHaveLength(1);
    expect(styleLiteralsIn(`fontSize: "0.85em"`)).toHaveLength(1);
  });

  it("accepts tokens, plain numbers, identifiers with digits, and comments", () => {
    expect(styleLiteralsIn(`<main style={{ padding: "var(--space-4)" }}>`)).toEqual([]);
    expect(styleLiteralsIn(`const pageSize = 50; const item2 = items[2];`)).toEqual([]);
    expect(styleLiteralsIn(`queryKey: ["hello", "greetings", locale]`)).toEqual([]);
    expect(styleLiteralsIn(`// the old value was #b00020 and 12px`)).toEqual([]);
    expect(styleLiteralsIn(` * 40 px is for money totals`)).toEqual([]);
  });
});

describe("the web modules", () => {
  // Every source file of every module, as text. Test files are left out: they hold sample data.
  const sources = import.meta.glob(["../modules/**/*.{ts,tsx,css}", "!../modules/**/*.test.{ts,tsx}"], {
    query: "?raw",
    import: "default",
    eager: true
  }) as Record<string, string>;

  it("are found by this test (a wrong path would make it pass for ever)", () => {
    expect(Object.keys(sources)).toContain("../modules/hello/HelloPage.tsx");
  });

  it("write no colour and no length of their own: tokens only", () => {
    const literals = Object.entries(sources).flatMap(([file, source]) =>
      styleLiteralsIn(source).map((found) => `${file.replace("../", "web/src/")}:${found}`)
    );
    expect(literals, "use a token of design/tokens.css: var(--color-...), var(--space-...), var(--font-size-...)").toEqual([]);
  });
});
