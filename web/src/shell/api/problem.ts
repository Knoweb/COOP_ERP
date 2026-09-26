// The error document of the API (RFC 9457 with our members; openapi/common.yaml), and how a
// response is read as one. In a file of its own so that the client and the step-up replay
// (pendingCommand.ts) share it without importing each other.

import type { components } from "../../generated/common";

export type Problem = components["schemas"]["Problem"];

/** The problem code the server answers when the action needs a second factor fresher than it has. */
export const STEP_UP_REQUIRED = "mfa.required";

/** What an error response says, whatever shape it has. */
export async function problemOf(response: Response): Promise<Problem> {
  if (response.headers.get("Content-Type")?.includes("application/problem+json")) {
    return (await response.json()) as Problem;
  }
  // Not from our API: a proxy, a gateway, a server that is starting.
  return { status: response.status, code: "unknown" };
}
