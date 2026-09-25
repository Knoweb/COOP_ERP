// The one way the web client talks to the API (17A section 7: "fetch wrapper: bearer,
// idempotency key, problem-details, step-up retry"). A module never calls fetch itself.
//
//   const api = useApiClient<paths>();          // `paths` from web/src/generated/<module>.ts
//   const { data } = await api.GET("/v1/hello/greetings");
//
// Paths, parameters, bodies and responses are checked by the compiler against the types
// generated from the module's OpenAPI slice (openapi-fetch over openapi-typescript), so a
// changed slice is a compile error here, exactly as it is for the controller on the server.
//
// What this adds to every request, so that no screen can forget it:
//   - the bearer token of the signed-in user: the server reads who the user is from it
//   - X-Scope-Entity, the entity the user acts in now (19A section 1: the active scope is
//     the caller's choice among the scopes the token and the assignments give)
//   - Accept-Language, so that error messages come back in the user's language
//   - a refusal to send a mutating request without an Idempotency-Key (see idempotency.ts)
// and what it does with every answer: an error response becomes a thrown ApiProblem, and a
// 401 that asks for a fresh second factor (mfa.required, 19A section 2) sends the user to the
// identity server to present it, back to the same page, where the action is taken again.

import { useMemo } from "react";
import { useIntl } from "react-intl";
import { useAuth } from "react-oidc-context";
import createClient, { type Client, type Middleware } from "openapi-fetch";
import type { components } from "../../generated/common";
import { useSession, type Session } from "../auth/session";

const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";
const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** The error document of the API (RFC 9457 with our members; openapi/common.yaml). */
export type Problem = components["schemas"]["Problem"];

/** The problem code the server answers when the action needs a second factor fresher than it has. */
export const STEP_UP_REQUIRED = "mfa.required";

/**
 * An error answer of the API. `problem.code` is a stable message id a screen may test for;
 * `problem.title` is already in the user's language, so a screen shows it as it is.
 */
export class ApiProblem extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.code);
    this.name = "ApiProblem";
  }

  /**
   * For a form: the message of each field the server refused (code request.invalid), by the
   * field's name in the request. Empty for every other problem.
   */
  get fieldErrors(): Record<string, string> {
    return Object.fromEntries((this.problem.errors ?? []).map((error) => [error.field, error.message ?? error.code]));
  }
}

/** What an error response says, whatever shape it has. */
export async function problemOf(response: Response): Promise<Problem> {
  if (response.headers.get("Content-Type")?.includes("application/problem+json")) {
    return (await response.json()) as Problem;
  }
  // Not from our API: a proxy, a gateway, a server that is starting.
  return { status: response.status, code: "unknown" };
}

export type RequestContext = {
  accessToken: string;
  locale: string;
  session: Session;
  /** Sends the user to the identity server for a fresh second factor; optional for tests. */
  stepUp?: () => void;
};

export function apiMiddleware(getContext: () => RequestContext): Middleware {
  return {
    onRequest({ request }) {
      if (MUTATING.has(request.method) && !request.headers.get("Idempotency-Key")) {
        // A programming error, caught on the first click and not in production: a key made
        // up here would be new on every retry, which is the one thing a key must not be.
        throw new Error(
          `${request.method} ${new URL(request.url).pathname}: no Idempotency-Key. ` +
            `Take one from useIdempotencyKey() and pass it in params.header.`
        );
      }
      const { accessToken, locale, session } = getContext();
      request.headers.set("Authorization", `Bearer ${accessToken}`);
      request.headers.set("Accept-Language", locale);
      if (session.entityId) {
        request.headers.set("X-Scope-Entity", session.entityId);
      }
      return request;
    },

    async onResponse({ response }) {
      if (!response.ok) {
        const problem = await problemOf(response);
        if (response.status === 401 && problem.code === STEP_UP_REQUIRED) {
          getContext().stepUp?.();
        }
        throw new ApiProblem(problem);
      }
      return response;
    }
  };
}

/**
 * The API client of one module, typed by that module's generated `paths`. Only inside
 * RequireLogin, which is everywhere: there is always a session.
 */
export function useApiClient<Paths extends object>(): Client<Paths> {
  const session = useSession();
  const { locale } = useIntl();
  const auth = useAuth();

  return useMemo(() => {
    if (!session) {
      throw new Error("useApiClient needs a signed-in user; is this outside RequireLogin?");
    }
    const client = createClient<Paths>({ baseUrl: API_BASE });
    // Step-up (doc 19 section 2.2: "the challenge is at the action, not at login"): a fresh
    // sign-in at the identity server, which asks for the second factor, and back to this page.
    const stepUp = () =>
      void auth.signinRedirect({
        prompt: "login",
        state: { returnTo: window.location.pathname + window.location.search }
      });
    client.use(apiMiddleware(() => ({ accessToken: session.accessToken, locale, session, stepUp })));
    return client;
  }, [session, locale, auth]);
}
