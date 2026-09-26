import { describe, expect, it, vi } from "vitest";
import { pendingCommandOf, replayPendingCommand, type PendingCommand } from "./pendingCommand";

const pending: PendingCommand = {
  method: "POST",
  url: "http://api.test/v1/party/entities/e-1/suspend",
  headers: { "Idempotency-Key": "key-7", "Content-Type": "application/json" },
  body: JSON.stringify({ reasonCode: "COMPLIANCE", reasonText: null })
};

const context = {
  accessToken: "the-fresh-token",
  locale: "si",
  session: { entityId: "0190f000-0000-7000-8000-000000000001" },
  locationId: null
};

const problem = (status: number, code: string, title?: string) =>
  new Response(JSON.stringify({ status, code, title }), { status, headers: { "Content-Type": "application/problem+json" } });

describe("the command a step-up interrupted", () => {
  it("is copied from the request with its key and body, and never with the bearer token", async () => {
    const request = new Request("http://api.test/v1/x", {
      method: "POST",
      headers: { "Idempotency-Key": "key-1", "Content-Type": "application/json", Authorization: "Bearer old" },
      body: "{}"
    });

    const copy = await pendingCommandOf(request);

    expect(copy).toEqual({ method: "POST", url: "http://api.test/v1/x", headers: { "Idempotency-Key": "key-1", "Content-Type": "application/json" }, body: "{}" });
    // The request itself can still be sent: the copy read a clone.
    expect(request.bodyUsed).toBe(false);
  });

  it("is not kept for a file upload: a multipart body does not fit in the login state", async () => {
    // The body as the browser encodes a FormData (jsdom's FormData and the Request of Node do
    // not agree with each other, so the encoded form is written here).
    const request = new Request("http://api.test/v1/party/bulk-register", {
      method: "POST",
      headers: { "Idempotency-Key": "key-2", "Content-Type": "multipart/form-data; boundary=----b" },
      body: '------b\r\nContent-Disposition: form-data; name="file"; filename="societies.csv"\r\n\r\na,b\r\n------b--\r\n'
    });

    expect(await pendingCommandOf(request)).toBeNull();
  });

  it("is sent again with the fresh token, the language and the scope, and the same key", async () => {
    const fetchFn = vi.fn(async () => new Response(null, { status: 204 }));

    const outcome = await replayPendingCommand(pending, { ...context, locationId: "loc-1" }, fetchFn as unknown as typeof fetch);

    expect(outcome).toEqual({ ok: true });
    const [url, init] = fetchFn.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(pending.url);
    expect(init.method).toBe("POST");
    expect(init.body).toBe(pending.body);
    const headers = init.headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer the-fresh-token");
    expect(headers.get("Accept-Language")).toBe("si");
    expect(headers.get("X-Scope-Entity")).toBe(context.session.entityId);
    expect(headers.get("X-Scope-Location")).toBe("loc-1");
    expect(headers.get("Idempotency-Key")).toBe("key-7");
  });

  it("reports the server's problem when the replay is refused, a second mfa.required included, and asks for no further step-up", async () => {
    const fetchFn = vi.fn(async () => problem(401, "mfa.required", "Fresh second factor needed"));

    const outcome = await replayPendingCommand(pending, context, fetchFn as unknown as typeof fetch);

    expect(outcome).toEqual({ ok: false, problem: { status: 401, code: "mfa.required", title: "Fresh second factor needed" } });
    expect(fetchFn).toHaveBeenCalledTimes(1);
  });
});
