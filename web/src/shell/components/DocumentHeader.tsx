import type { ReactNode } from "react";
import { StateChip } from "./StateChip";
import type { ChipState } from "./StateChip";

/**
 * The head of a document or register card (doc 30 section 2.2, "DocumentHeader-style card"):
 * the code, the title, an optional line under it, the current state as a chip, and the facts
 * a reader wants at a glance as label/value pairs. What goes in it is the screen's business;
 * how it looks is the shell's, so a GRN, a society and a price list read the same way.
 *
 * Every text arrives ALREADY TRANSLATED: the header shows what it is given and translates
 * nothing itself. A fact with no value shows an em dash, never an empty cell, so a reader can
 * tell "not set" from "not loaded".
 */
export type DocumentFact = {
  /** Translated label, for example t("m1.card.registration").text. */
  label: string;
  value?: ReactNode;
};

type DocumentHeaderProps = {
  /** The identifier a person quotes: M042, GRN-2026-000123. */
  code: string;
  title: string;
  subtitle?: ReactNode;
  state?: { look: ChipState; label: string };
  facts?: DocumentFact[];
  /** Actions that belong to the header, an ApprovalBar as a rule. */
  children?: ReactNode;
};

export function DocumentHeader({ code, title, subtitle, state, facts = [], children }: DocumentHeaderProps) {
  return (
    <header className="document-header">
      <div className="document-header__top">
        <div>
          <span className="document-header__code">{code}</span>
          <h1 className="document-header__title">{title}</h1>
          {subtitle && <div className="document-header__subtitle">{subtitle}</div>}
        </div>
        {state && <StateChip state={state.look} label={state.label} />}
      </div>
      {facts.length > 0 && (
        <dl className="document-header__facts">
          {facts.map((fact) => (
            <div key={fact.label} className="document-header__fact">
              <dt>{fact.label}</dt>
              <dd>{fact.value === undefined || fact.value === null || fact.value === "" ? "—" : fact.value}</dd>
            </div>
          ))}
        </dl>
      )}
      {children}
    </header>
  );
}
