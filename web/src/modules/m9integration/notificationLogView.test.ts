import { describe, expect, it } from "vitest";
import { deferredUntil, recipientParty } from "./notificationLogView";

const OWN = "0190e9b0-0000-7000-8000-000000000001";
const BUYER = "0190e9b0-0000-7000-8000-0000000000b2";

describe("whom a delivery reached, without the number (M9-07)", () => {
  it("is the reader's own entity", () => {
    expect(recipientParty({ recipientEntityId: OWN }, OWN)).toEqual({ kind: "own", shortId: null });
  });

  it("is another entity, by the end of its id", () => {
    expect(recipientParty({ recipientEntityId: BUYER }, OWN)).toEqual({ kind: "other", shortId: "000000b2" });
  });

  it("is unknown for an explicit recipient or a row from before the log kept it", () => {
    expect(recipientParty({ recipientEntityId: null }, OWN)).toEqual({ kind: "unknown", shortId: null });
    expect(recipientParty({}, OWN)).toEqual({ kind: "unknown", shortId: null });
  });
});

describe("a delivery deferred by quiet hours (CR-19A-12)", () => {
  // Fixed instants: the test never depends on today.
  const now = new Date("2026-10-06T16:00:00Z");

  it("is a QUEUED row with no attempt and its next attempt ahead", () => {
    expect(deferredUntil({ status: "QUEUED", attempts: 0, nextAttemptAt: "2026-10-07T01:30:00Z" }, now)).toBe(
      "2026-10-07T01:30:00Z"
    );
  });

  it("is not a retry waiting for its backoff, a row due now, or a settled row", () => {
    expect(deferredUntil({ status: "QUEUED", attempts: 1, nextAttemptAt: "2026-10-07T01:30:00Z" }, now)).toBeNull();
    expect(deferredUntil({ status: "QUEUED", attempts: 0, nextAttemptAt: "2026-10-06T15:59:00Z" }, now)).toBeNull();
    expect(deferredUntil({ status: "SENT", attempts: 0, nextAttemptAt: null }, now)).toBeNull();
  });
});
