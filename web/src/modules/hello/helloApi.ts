// Calls to the hello API, typed by the client generated from openapi/hello.yaml.
// Never write a request or response type by hand: change the slice, run `make gen-clients`.
//
// Stub note (S0-07): the shell's api/client.ts will own the fetch wrapper (bearer token,
// idempotency key, problem details, step-up retry). Until it exists this file does the two
// things a caller cannot skip: a fresh Idempotency-Key per user action, and the scope header.
// Before login exists, the scope is a development default: the MPCS of infra/compose/realm-dev.json.

import type { components } from "../../generated/hello";

export type Greeting = components["schemas"]["GreetingResponse"];
export type RegisterGreetingRequest = components["schemas"]["RegisterGreetingRequest"];
export type Problem = components["schemas"]["Problem"];

const API_BASE = import.meta.env.VITE_API_BASE || "http://localhost:8080";
const DEV_SCOPE_ENTITY =
  import.meta.env.VITE_DEV_SCOPE_ENTITY || "0190f000-0000-7000-8000-000000000002";

const GREETINGS = `${API_BASE}/v1/hello/greetings`;

/** An error response of the API: `code` is a stable message id, `title` is already translated. */
export class ApiProblem extends Error {
  constructor(public readonly problem: Problem) {
    super(problem.code);
  }
}

function headers(locale: string): Record<string, string> {
  return {
    "Accept-Language": locale,
    "X-Scope-Entity": DEV_SCOPE_ENTITY
  };
}

async function readOrThrow<T>(response: Response): Promise<T> {
  if (response.ok) {
    return response.json() as Promise<T>;
  }
  const isProblem = response.headers.get("Content-Type")?.includes("application/problem+json");
  throw new ApiProblem(
    isProblem ? ((await response.json()) as Problem) : { status: response.status, code: "unknown" }
  );
}

export async function listGreetings(locale: string): Promise<Greeting[]> {
  return readOrThrow(await fetch(GREETINGS, { headers: headers(locale) }));
}

/**
 * `idempotencyKey` is created once per user action by the caller and reused when that same
 * action is retried, so a retry can never register the greeting twice.
 */
export async function registerGreeting(
  request: RegisterGreetingRequest,
  idempotencyKey: string,
  locale: string
): Promise<Greeting> {
  return readOrThrow(
    await fetch(GREETINGS, {
      method: "POST",
      headers: {
        ...headers(locale),
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey
      },
      body: JSON.stringify(request)
    })
  );
}
