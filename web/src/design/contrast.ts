// Contrast of the colour tokens, by the WCAG 2.2 formula. Used by two readers:
//   - design/tokens.test.ts fails the build when a pair below falls under its minimum;
//   - the reference page /_design shows each pair with its measured ratio.
// Both read the real design/tokens.css, so neither can drift away from what the screens use.

/** WCAG 2.2, success criterion 1.4.3: body text. */
export const TEXT = 4.5;
/** WCAG 2.2, 1.4.3 and 1.4.11: large text (from 24 px, or 19 px bold), icons, the edges of controls. */
export const LARGE_OR_ICON = 3;

export type ContrastPair = {
  /** The token of the text, symbol or edge. */
  foreground: string;
  /** The token of what lies behind it. */
  background: string;
  /** TEXT or LARGE_OR_ICON. */
  minimum: number;
};

/**
 * Every combination of a foreground and a background that a screen may use. If you put a text
 * colour on a background that is not listed here, add the pair first and see whether it passes.
 */
export const CONTRAST_PAIRS: ContrastPair[] = [
  { foreground: "--color-text", background: "--color-surface", minimum: TEXT },
  { foreground: "--color-text", background: "--color-surface-subtle", minimum: TEXT },
  { foreground: "--color-text-muted", background: "--color-surface", minimum: TEXT },
  { foreground: "--color-text-muted", background: "--color-surface-subtle", minimum: TEXT },
  { foreground: "--color-border-control", background: "--color-surface", minimum: LARGE_OR_ICON },
  { foreground: "--color-border-control", background: "--color-surface-subtle", minimum: LARGE_OR_ICON },

  { foreground: "--color-accent", background: "--color-surface", minimum: TEXT },
  { foreground: "--color-accent", background: "--color-surface-subtle", minimum: TEXT },
  { foreground: "--color-on-accent", background: "--color-accent", minimum: TEXT },

  // The state chips: text and symbol on the background of the same state.
  { foreground: "--color-draft-text", background: "--color-draft-bg", minimum: TEXT },
  { foreground: "--color-issued-text", background: "--color-issued-bg", minimum: TEXT },
  { foreground: "--color-disputed-text", background: "--color-disputed-bg", minimum: TEXT },
  { foreground: "--color-void-text", background: "--color-void-bg", minimum: TEXT },
  // The void chip is hatched: its text lies on both stripes, so it must be readable on both.
  { foreground: "--color-void-text", background: "--color-void-hatch", minimum: TEXT },
  { foreground: "--color-alert-text", background: "--color-alert-bg", minimum: TEXT },
  // A field error is alert text straight on the page.
  { foreground: "--color-alert-text", background: "--color-surface", minimum: TEXT },
  // The scope banner writes normal text on the disputed and alert backgrounds.
  { foreground: "--color-text", background: "--color-disputed-bg", minimum: TEXT },
  { foreground: "--color-text", background: "--color-alert-bg", minimum: TEXT },

  // Training mode: the band, the frame around the page, the header.
  { foreground: "--color-on-training", background: "--color-training", minimum: TEXT },
  { foreground: "--color-training", background: "--color-surface", minimum: LARGE_OR_ICON },
  { foreground: "--color-text", background: "--color-training-bg", minimum: TEXT },
  { foreground: "--color-text-muted", background: "--color-training-bg", minimum: TEXT }
];

/**
 * Colours that carry no information, so WCAG asks nothing of them: a divider between two rows
 * can vanish and the screen still reads the same. Think twice before adding to this list.
 */
export const DECORATIVE_COLOURS = ["--color-border"];

/** The custom properties of a CSS text: { "--color-text": "#1f2933", ... }. Comments are ignored. */
export function parseTokens(css: string): Record<string, string> {
  const withoutComments = css.replace(/\/\*[\s\S]*?\*\//g, "");
  const tokens: Record<string, string> = {};
  for (const match of withoutComments.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/g)) {
    tokens[match[1]] = match[2].trim();
  }
  return tokens;
}

/**
 * The contrast ratio of two colours, from 1 (the same) to 21 (black on white). Only the form
 * #rrggbb is understood, on purpose: a colour with transparency has no contrast of its own, it
 * depends on what shines through, and then nothing can be checked.
 */
export function contrastRatio(hexA: string, hexB: string): number {
  const lighter = Math.max(luminance(hexA), luminance(hexB));
  const darker = Math.min(luminance(hexA), luminance(hexB));
  return (lighter + 0.05) / (darker + 0.05);
}

/** Relative luminance, WCAG 2.2: each channel made linear, then weighted by how bright the eye finds it. */
function luminance(hex: string): number {
  if (!/^#[0-9a-f]{6}$/i.test(hex)) {
    throw new Error(`"${hex}" is not a colour of the form #rrggbb`);
  }
  const [red, green, blue] = [1, 3, 5]
    .map((start) => parseInt(hex.slice(start, start + 2), 16) / 255)
    .map((channel) => (channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4)));
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}
