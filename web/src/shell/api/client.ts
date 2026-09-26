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
//   - X-Scope-Entity, the entity the user acts in now, and X-Scope-Location when the user acts
//     at one location of it (19A section 1: the active scope is the caller's choice among the
//     scopes the token and the assignments give)
//   - Accept-Language, so that error messages come back in the user's language
//   - a refusal to send a mutating request without an Idempotency-Key (see idempotency.ts)
// and what it does with every answer: an error response becomes a thrown ApiProblem, and a
// 401 that asks for a fresh second factor (mfa.required, 19A section 2) sends the user to the
// identity server to present it, back to the same page, where the interrupted command is
// taken again once with the fresher token (pendingCommand.ts, StepUpReplay.tsx).

import { useContext, useMemo } from "react";
import { useIntl } from "react-intl";
import { useAuth } from "react-oidc-context";
import createClient, { type Client, type Middleware } from "openapi-fetch";
import { STEP_UP_ACR_VALUES, type LoginState } from "../auth/oidc";
import { useSession, type Session } from "../auth/session";
import { ScopeContext } from "../scope/ScopeContext";
import { pendingCommandOf, type PendingCommand } from "./pendingCommand";
import { problemOf, STEP_UP_REQUIRED, type Problem } from "./problem";

// The problem document and its reader live in problem.ts; screens keep importing them from here.
export { problemOf, STEP_UP_REQUIRED, type Problem };

const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";
const MUTATING = new Set(["POST", "PUT", "PATCH", "DELETE"]);

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

export type RequestContext = {
  accessToken: string;
  locale: string;
  session: Session;
  /** The location the user acts at; null or absent for the whole entity. */
  locationId?: string | null;
  /**
   * Sends the user to the identity server for a fresh second factor, carrying the command
   * that was refused so that it can be taken again after the sign-in; null when the command
   * cannot be carried (a file upload). Optional for tests.
   */
  stepUp?: (pending: PendingCommand | null) => void;
};

export function apiMiddleware(getContext: () => RequestContext): Middleware {
  // The command each mutating request carries, kept until the answer arrives.
  const pendingOf = new WeakMap<Request, PendingCommand | null>();
  // One step-up per page life: the redirect leaves the page, and two requests refused at the
  // same moment must not each start a sign-in. The first one carries its command; the rest
  // are reported as problems and the person takes them again on return.
  let steppingUp = false;

  return {
    async onRequest({ request }) {
      if (MUTATING.has(request.method) && !request.headers.get("Idempotency-Key")) {
        // A programming error, caught on the first click and not in production: a key made
        // up here would be new on every retry, which is the one thing a key must not be.
        throw new Error(
          `${request.method} ${new URL(request.url).pathname}: no Idempotency-Key. ` +
            `Take one from useIdempotencyKey() and pass it in params.header.`
        );
      }
      const { accessToken, locale, session, locationId } = getContext();
      request.headers.set("Authorization", `Bearer ${accessToken}`);
      request.headers.set("Accept-Language", locale);
      if (session.entityId) {
        request.headers.set("X-Scope-Entity", session.entityId);
      }
      if (locationId) {
        request.headers.set("X-Scope-Location", locationId);
      }
      if (MUTATING.has(request.method)) {
        pendingOf.set(request, await pendingCommandOf(request));
      }
      return request;
    },

    async onResponse({ request, response }) {
      if (!response.ok) {
        const problem = await problemOf(response);
        if (response.status === 401 && problem.code === STEP_UP_REQUIRED && !steppingUp) {
          steppingUp = true;
          getContext().stepUp?.(pendingOf.get(request) ?? null);
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
  // Null outside the ScopeProvider (a test); the scope then is the whole entity.
  const locationId = useContext(ScopeContext)?.active.locationId ?? null;

  return useMemo(() => {
    if (!session) {
      throw new Error("useApiClient needs a signed-in user; is this outside RequireLogin?");
    }
    const client = createClient<Paths>({ baseUrl: API_BASE });
    // Step-up (doc 19 section 2.2: "the challenge is at the action, not at login"): a fresh
    // sign-in at the identity server, which asks for the second factor (acr_values names it
    // where the realm has one; oidc.ts), and back to this page with the refused command.
    const stepUp = (pending: PendingCommand | null) => {
      const state: LoginState = {
        returnTo: window.location.pathname + window.location.search,
        pendingCommand: pending ?? undefined
      };
      void auth.signinRedirect({
        prompt: "login",
        ...(STEP_UP_ACR_VALUES ? { acr_values: STEP_UP_ACR_VALUES } : {}),
        state
      });
    };
    client.use(apiMiddleware(() => ({ accessToken: session.accessToken, locale, session, locationId, stepUp })));
    return client;
  }, [session, locale, auth, locationId]);
}
