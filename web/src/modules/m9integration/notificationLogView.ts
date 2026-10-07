// The pure helpers of the delivery log (wave 2: M9-07, CR-19A-12), kept apart so that they are
// tested without a browser (notificationLogView.test.ts).

import type { NotificationLogEntry } from "./integrationApi";

/** How many characters of an entity id the log shows: the END, as the scope banner does (UUIDv7 ids begin alike). */
const SHORT_ID_LENGTH = 8;

/**
 * Whose contact a row reached, never the number: the reader's own entity, another entity (the
 * counterparty) by the end of its id, or unknown (an explicit recipient, a direct send, or a row
 * written before the log kept it).
 */
export function recipientParty(
  entry: Pick<NotificationLogEntry, "recipientEntityId">,
  activeEntityId: string | null
): { kind: "own" | "other" | "unknown"; shortId: string | null } {
  const entity = entry.recipientEntityId ?? null;
  if (!entity) {
    return { kind: "unknown", shortId: null };
  }
  if (activeEntityId && entity === activeEntityId) {
    return { kind: "own", shortId: null };
  }
  return { kind: "other", shortId: entity.slice(-SHORT_ID_LENGTH) };
}

/**
 * When a row was deferred by quiet hours, the moment it is due; null otherwise. A deferral is
 * not a new status (CR-19A-12): a QUEUED row with no attempt made and its next attempt in the
 * future is one.
 */
export function deferredUntil(
  entry: Pick<NotificationLogEntry, "status" | "attempts" | "nextAttemptAt">,
  now: Date
): string | null {
  if (entry.status !== "QUEUED" || entry.attempts !== 0 || !entry.nextAttemptAt) {
    return null;
  }
  return new Date(entry.nextAttemptAt).getTime() > now.getTime() ? entry.nextAttemptAt : null;
}
