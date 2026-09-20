import type { ReactNode } from "react";
import { useT } from "../i18n/useT";

/**
 * The states a chip can show (doc 30 section 2.1), each with its symbol. They are the five LOOKS
 * of the design system, not the states of a particular document: a module's state machine has
 * its own words ("confirmed", "received", "counted") and picks the look that fits, with its own
 * label.
 *
 * To add a look: a line here, a `.state-chip--<name>` rule in shell/shell.css, the two colour
 * tokens in design/tokens.css with their pair in design/tokens.test.ts, and the message
 * `shell.state.<name>` in three languages. The tests fail until all of them exist.
 *
 * The symbols are drawn here as plain SVG, 16 x 16, in the colour of the text. No icon library:
 * doc 30 asks for ONE icon set following the paper metaphor, and choosing it belongs to the
 * component library decision (open item E-03, deferred). Replace these five when it is taken.
 */
const SYMBOLS = {
  // A pencil stroke: still being written.
  draft: <path d="M3 13 L4 10 L11 3 L13 5 L6 12 Z" />,
  // A tick: issued and in force.
  issued: <path d="M3 8.5 L6.5 12 L13 4.5" />,
  // A question mark: somebody does not agree.
  disputed: (
    <>
      <path d="M5.5 6 A2.5 2.5 0 1 1 8 8.5 L8 10" />
      <path d="M8 12.5 L8 13" />
    </>
  ),
  // A circle with a bar through it: cancelled, kept for the record.
  void: (
    <>
      <circle cx="8" cy="8" r="5.5" />
      <path d="M4.2 11.8 L11.8 4.2" />
    </>
  ),
  // A warning triangle with an exclamation mark: needs attention now.
  alert: (
    <>
      <path d="M8 2.5 L14 13 L2 13 Z" />
      <path d="M8 6.5 L8 9.5" />
      <path d="M8 11.2 L8 11.6" />
    </>
  )
} satisfies Record<string, ReactNode>;

export type ChipState = keyof typeof SYMBOLS;

/** Every look there is, for the reference page and the tests. */
export const CHIP_STATES = Object.keys(SYMBOLS) as ChipState[];

type StateChipProps = {
  state: ChipState;
  /**
   * The word on the chip, ALREADY TRANSLATED by the caller: t("trading.grn.state.confirmed").text.
   * Left out, the chip shows the general word of the look ("Draft", "Issued" ...).
   */
  label?: string;
};

/**
 * The current state of a document (doc 30 section 2.2), as a small chip: symbol, then the word.
 *
 * Colour is never the only signal (doc 30, P-08). The word is always there and is what a screen
 * reader reads; the symbol is hidden from it, because it says the same thing again. Somebody who
 * cannot tell green from amber still has the tick and the question mark, and the dashed edge of
 * a draft.
 *
 * It is plain text, not a live region: when a command changes the state, telling the user is the
 * job of the confirmation the screen shows (doc 30: "a toast plus the state chip changing").
 */
export function StateChip({ state, label }: StateChipProps) {
  const t = useT();
  return (
    <span className={`state-chip state-chip--${state}`}>
      <svg
        className="state-chip__symbol"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
        focusable="false"
      >
        {SYMBOLS[state]}
      </svg>
      {label ?? t(`shell.state.${state}`).text}
    </span>
  );
}
