// The command a step-up interrupted, and how it is taken again after the fresh sign-in.
//
// The step-up (doc 19 section 2.2, "the challenge is at the action, not at login") is a full
// redirect to the identity server. The page is unloaded on the way, and with it the in-memory
// token, the mutation, the reason the clerk typed and the idempotency key. So what the server
// refused with mfa.required is kept HERE, as plain data, inside the state of the login request
// (oidc-client-ts stores that state in session storage until the browser comes back), and
// replayed exactly once with the fresher token. The same idempotency key goes with it, so a
// command the server had in fact already run is answered with its first result and not run
// twice (AGENTS.md: every mutating operation carries an Idempotency-Key).
//
// What is NOT kept: the bearer token (it is the old one; the new session has a new one) and a
// multipart body (a file is too large for the login state; the bulk upload is asked for again).

import { problemOf, type Problem } from "./problem";
import type { Session } from "../auth/session";

export type PendingCommand = {
  method: string;
  url: string;
  /** The request's own headers: Idempotency-Key, Content-Type. Never Authorization. */
  headers: Record<string, string>;
  /** The JSON body as text, or null for a command without one. */
  body: string | null;
};

/** The headers every request of a signed-in user carries (shell/api/client.ts adds the same). */
export type ReplayContext = {
  accessToken: string;
  locale: string;
  session: Pick<Session, "entityId">;
  /** The location the user acts at, when the scope is narrowed to one. */
  locationId?: string | null;
};

export type ReplayOutcome = { ok: true } | { ok: false; problem: Problem };

/** The headers of a request worth keeping: what names the command, never what names the user. */
const KEPT_HEADERS = ["Idempotency-Key", "Content-Type"];

/**
 * A copy of a request as plain data, or null when it cannot be replayed (a multipart body).
 * Reads the body from a clone, so the request itself is still sent.
 */
export async function pendingCommandOf(request: Request): Promise<PendingCommand | null> {
  const contentType = request.headers.get("Content-Type") ?? "";
  if (contentType.startsWith("multipart/")) {
    return null;
  }
  const headers: Record<string, string> = {};
  for (const name of KEPT_HEADERS) {
    const value = request.headers.get(name);
    if (value !== null) {
      headers[name] = value;
    }
  }
  const body = request.method === "GET" || request.method === "HEAD" ? null : await request.clone().text();
  return { method: request.method, url: request.url, headers, body: body === "" ? null : body };
}

/**
 * Sends the kept command once more, with the fresh token and the same scope headers. A second
 * mfa.required is reported like any other problem and never starts another step-up: one
 * replay, then the person decides.
 */
export async function replayPendingCommand(
  pending: PendingCommand,
  context: ReplayContext,
  fetchFn: typeof fetch = fetch
): Promise<ReplayOutcome> {
  const headers = new Headers(pending.headers);
  headers.set("Authorization", `Bearer ${context.accessToken}`);
  headers.set("Accept-Language", context.locale);
  if (context.session.entityId) {
    headers.set("X-Scope-Entity", context.session.entityId);
  }
  if (context.locationId) {
    headers.set("X-Scope-Location", context.locationId);
  }
  const response = await fetchFn(pending.url, { method: pending.method, headers, body: pending.body });
  if (response.ok) {
    return { ok: true };
  }
  return { ok: false, problem: await problemOf(response) };
}
