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
// What is NOT kept: the bearer token (it is the old one; the new session has a new one), a
// multipart body (a file is too large for the login state; the bulk upload is asked for again),
// a command whose URL is not under the API base, and a command whose JSON body has a key the
// kernel refuses in an event payload (NIC, phone, ...; forbiddenFields.ts). Session storage is
// readable by any script of the origin, and a replay would send the new bearer token to the
// stored URL. For those the person signs in, and the form is shown again (CR-30-2).

import { resolveConfig } from "../runtimeConfig";
import { hasForbiddenField } from "./forbiddenFields";
import { problemOf, type Problem } from "./problem";
import type { Session } from "../auth/session";

export type PendingCommand = {
  method: string;
  url: string;
  /**
   * The request's own headers: Idempotency-Key, Content-Type, and the scope it was sent in
   * (X-Scope-Entity, X-Scope-Location: ids of an entity and a location, not personal data).
   * Never Authorization.
   */
  headers: Record<string, string>;
  /** The JSON body as text, or null for a command without one. */
  body: string | null;
  /**
   * The `sub` of the user who sent it. The command is taken again only for that same user: on a
   * shared counter PC another person may complete the sign-in, and must not run a command they
   * never confirmed (oidc.ts keepsFor, StepUpReplay.tsx).
   */
  subject?: string;
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

/**
 * The headers of a request worth keeping: what names the command and the scope it was sent in,
 * never what names the user. The scope is kept because the replay runs on the first render after
 * the sign-in, before the session read has told the shell the user's location, and a
 * one-location user's command sent without X-Scope-Location is refused.
 */
const KEPT_HEADERS = ["Idempotency-Key", "Content-Type", "X-Scope-Entity", "X-Scope-Location"];

/** True when `url` is the API base itself or a path under it ("https://api.x" does not cover "https://api.x.evil"). */
export function isUnderApiBase(url: string, apiBase: string): boolean {
  const base = apiBase.replace(/\/+$/, "");
  return url === base || url.startsWith(`${base}/`) || url.startsWith(`${base}?`);
}

/**
 * Whether a command may be kept in the login state while the person signs in again (CR-30-2):
 * its URL is under the API base (the bearer token is never sent anywhere else) and no key of its
 * JSON body is one the kernel refuses in an event payload (a NIC, a phone: browser storage is
 * the same class of place as a log line). A body that is not JSON is not kept either.
 */
export function mayKeep(pending: PendingCommand, apiBase: string): boolean {
  if (!isUnderApiBase(pending.url, apiBase)) {
    return false;
  }
  if (pending.body === null) {
    return true;
  }
  try {
    return !hasForbiddenField(JSON.parse(pending.body));
  } catch {
    return false;
  }
}

/**
 * A copy of a request as plain data, or null when it must not or cannot be replayed: a
 * multipart body (too large for the login state), a URL outside the API base, or a body with a
 * personal or secret field (mayKeep). The step-up then goes ahead without it, and the form is
 * shown again. Reads the body from a clone, so the request itself is still sent.
 */
export async function pendingCommandOf(request: Request, apiBase: string = resolveConfig().apiBase): Promise<PendingCommand | null> {
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
  const pending = { method: request.method, url: request.url, headers, body: body === "" ? null : body };
  return mayKeep(pending, apiBase) ? pending : null;
}

/**
 * Sends the kept command once more, with the fresh token and the scope it was first sent in (the
 * context's scope only when the kept command names none). A second
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
  // The kept scope is taken whole (an entity-wide command stays entity-wide); only a command
  // that names no scope at all gets the scope the shell knows now.
  const keptScope = headers.has("X-Scope-Entity") || headers.has("X-Scope-Location");
  if (!keptScope && context.session.entityId) {
    headers.set("X-Scope-Entity", context.session.entityId);
  }
  if (!keptScope && context.locationId) {
    headers.set("X-Scope-Location", context.locationId);
  }
  const response = await fetchFn(pending.url, { method: pending.method, headers, body: pending.body });
  if (response.ok) {
    return { ok: true };
  }
  return { ok: false, problem: await problemOf(response) };
}
