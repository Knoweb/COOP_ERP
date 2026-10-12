import { describe, expect, it } from "vitest";
import type { PendingCommand } from "../api/pendingCommand";
import { keepsFor } from "./oidc";

const pending: PendingCommand = {
  method: "POST",
  url: "http://api.test/v1/party/entities/e-1/suspend",
  headers: { "Idempotency-Key": "k" },
  body: "{}",
  subject: "0190f000-0000-7000-8000-0000000000aa"
};

describe("the command a sign-in brings back", () => {
  it("is kept for the user who sent it", () => {
    expect(keepsFor(pending, "0190f000-0000-7000-8000-0000000000aa", "http://api.test")).toBe(true);
  });

  it("is dropped when someone else signed in, or when it does not say whose it is", () => {
    expect(keepsFor(pending, "0190f000-0000-7000-8000-0000000000bb", "http://api.test")).toBe(false);
    expect(keepsFor(pending, undefined, "http://api.test")).toBe(false);
    expect(keepsFor({ ...pending, subject: undefined }, undefined, "http://api.test")).toBe(false);
  });

  it("is dropped when its URL is not under the API base, whoever signed in", () => {
    expect(keepsFor({ ...pending, url: "http://elsewhere.test/v1/x" }, pending.subject, "http://api.test")).toBe(false);
  });
});
