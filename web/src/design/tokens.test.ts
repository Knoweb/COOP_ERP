import { describe, expect, it } from "vitest";
// "?raw" gives the text of the file as it is on disk: the test reads what the screens use.
import tokensCss from "./tokens.css?raw";
import shellCss from "../shell/shell.css?raw";
import { CONTRAST_PAIRS, contrastRatio, DECORATIVE_COLOURS, parseTokens } from "./contrast";

const tokens = parseTokens(tokensCss);

/** "0.75rem" is 12 px, "1px" is 1 px. The tokens use nothing else for a length. */
function px(value: string): number {
  if (value.endsWith("rem")) {
    return parseFloat(value) * 16;
  }
  if (value.endsWith("px")) {
    return parseFloat(value);
  }
  throw new Error(`"${value}" is neither rem nor px`);
}

describe("the contrast formula", () => {
  it("gives 21 for black on white and 1 for a colour on itself", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 5);
    expect(contrastRatio("#1b5e20", "#1b5e20")).toBeCloseTo(1, 5);
  });

  it("agrees with the published value for the grey that just passes on white", () => {
    // #767676 on white is the well-known lightest grey that reaches 4.5:1 (4.54).
    expect(contrastRatio("#767676", "#ffffff")).toBeCloseTo(4.54, 2);
  });

  it("refuses a colour it cannot measure instead of guessing", () => {
    expect(() => contrastRatio("rgba(0,0,0,0.5)", "#ffffff")).toThrow(/not a colour of the form #rrggbb/);
  });
});

describe("the colour tokens", () => {
  it("reach the contrast WCAG 2.2 asks for, in every pair a screen may use", () => {
    const tooWeak = CONTRAST_PAIRS.map((pair) => ({
      ...pair,
      ratio: contrastRatio(tokens[pair.foreground], tokens[pair.background])
    }))
      .filter((pair) => pair.ratio < pair.minimum)
      .map((pair) => `${pair.foreground} on ${pair.background}: ${pair.ratio.toFixed(2)}:1, needs ${pair.minimum}:1`);
    expect(tooWeak).toEqual([]);
  });

  it("are all checked: a new colour is in a contrast pair, or is listed as decorative", () => {
    const checked = new Set([...CONTRAST_PAIRS.flatMap((pair) => [pair.foreground, pair.background]), ...DECORATIVE_COLOURS]);
    const unchecked = Object.keys(tokens).filter((name) => name.startsWith("--color-") && !checked.has(name));
    expect(unchecked).toEqual([]);
  });

  it("exist for every name the contrast pairs use", () => {
    const names = [...CONTRAST_PAIRS.flatMap((pair) => [pair.foreground, pair.background]), ...DECORATIVE_COLOURS];
    expect(names.filter((name) => !(name in tokens))).toEqual([]);
  });

  it("have a text and a background colour for each of the five document states", () => {
    for (const state of ["draft", "issued", "disputed", "void", "alert"]) {
      expect(tokens[`--color-${state}-text`], state).toBeDefined();
      expect(tokens[`--color-${state}-bg`], state).toBeDefined();
    }
  });
});

describe("the type, space and target tokens", () => {
  it("are the one type scale of doc 30: 12, 14, 16, 20, 28 and 40 px", () => {
    const sizes = Object.keys(tokens)
      .filter((name) => name.startsWith("--font-size-"))
      .map((name) => px(tokens[name]));
    expect(sizes.sort((a, b) => a - b)).toEqual([12, 14, 16, 20, 28, 40]);
  });

  it("keep the line height at 1.5, which Sinhala and Tamil need", () => {
    expect(tokens["--line-height"]).toBe("1.5");
  });

  it("put Noto Sans first, then Noto Sans Sinhala, then Noto Sans Tamil", () => {
    expect(tokens["--font-ui"].replace(/\s+/g, " ")).toMatch(/^"Noto Sans", "Noto Sans Sinhala", "Noto Sans Tamil", /);
  });

  it("keep every distance on the 8 px grid: --space-N is N times 8 px, the half step is 4 px", () => {
    const offGrid = Object.keys(tokens)
      .filter((name) => /^--space-\d+$/.test(name))
      .filter((name) => px(tokens[name]) !== Number(name.replace("--space-", "")) * 8);
    expect(offGrid).toEqual([]);
    expect(px(tokens["--space-half"])).toBe(4);
  });

  it("make a target at least 48 px with 12 px to the next one", () => {
    expect(px(tokens["--target-min"])).toBe(48);
    expect(px(tokens["--target-gap"])).toBe(12);
  });
});

describe("the style sheets", () => {
  it("keep tokens.css to tokens: :root is its only selector", () => {
    const withoutComments = tokensCss.replace(/\/\*[\s\S]*?\*\//g, "");
    const selectors = [...withoutComments.matchAll(/([^{}]+)\{/g)].map((match) => match[1].trim());
    expect(selectors).toEqual([":root"]);
  });

  it("write no colour in shell.css: every colour there is a token", () => {
    const withoutComments = shellCss.replace(/\/\*[\s\S]*?\*\//g, "");
    const literals = withoutComments.match(/#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/gi) ?? [];
    expect(literals).toEqual([]);
  });

  it("use no token in shell.css that tokens.css does not define", () => {
    // var(--colour-text) with a typing mistake is not an error for the browser: the rule is
    // dropped without a word and the text comes out in the wrong colour.
    const used = [...shellCss.matchAll(/var\((--[a-z0-9-]+)\)/g)].map((match) => match[1]);
    expect([...new Set(used)].filter((name) => !(name in tokens))).toEqual([]);
  });
});
