// How the screens of the party module show a society: the chip look of each status, the legal
// name in the user's language, and the codes a status change can be given as a reason.
// One place, so the list, the card and the tests agree.

import type { ChipState } from "../../shell/components/StateChip";
import type { Society, SocietyStatus } from "./partyApi";

/** The look of the shell's chip for each status of doc 21A (ONBOARDING, ACTIVE, SUSPENDED). */
export const STATUS_LOOK: Record<SocietyStatus, ChipState> = {
  ONBOARDING: "draft",
  ACTIVE: "issued",
  SUSPENDED: "alert"
};

export const STATUSES: SocietyStatus[] = ["ONBOARDING", "ACTIVE", "SUSPENDED"];

/** The message id of a status word: party.status.ACTIVE and so on. */
export function statusMessageId(status: SocietyStatus): string {
  return `party.status.${status}`;
}

/** The legal name in the user's language, or null when it is not translated (the caller shows English with the EN tag). */
export function legalNameIn(society: Society, locale: string): string | null {
  const name = locale === "si" ? society.legalNameSi : locale === "ta" ? society.legalNameTa : society.legalNameEn;
  return name || null;
}

/**
 * The reason codes a suspension or a reinstatement may carry. They are the module's own
 * vocabulary (the API takes any code); their words are party.reason.<code> in the catalogue.
 */
export const SUSPEND_REASONS = ["COMPLIANCE", "ARREARS", "REQUESTED", "OTHER"] as const;
export const REINSTATE_REASONS = ["RESOLVED", "REQUESTED", "OTHER"] as const;
