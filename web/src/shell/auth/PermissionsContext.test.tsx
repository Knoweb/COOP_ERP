import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiProblem, SCOPE_UNNAMED } from "../api/client";
import { PermissionsProvider, usePermissions } from "./PermissionsContext";

// The session read (GET /v1/session), played without a server: each call records the headers it
// was given and answers from `answer`.
const calls: Array<Record<string, string> | undefined> = [];
let answer: (headers: Record<string, string> | undefined) => unknown;
vi.mock("./session", () => ({ useSession: () => ({ accessToken: "t", policyClass: "OWN" }) }));
// A whole stand-in, not the actual module spread: the actual client imports the ScopeProvider,
// which imports this provider, which would then hold the real client.
vi.mock("../api/client", () => {
  class ApiProblem extends Error {
    constructor(public readonly problem: { code: string }) {
      super(problem.code);
    }
  }
  return {
    ApiProblem,
    SCOPE_UNNAMED: "X-Client-Scope-Unnamed",
    useApiClient: () => ({
      GET: async (_path: string, init?: { headers?: Record<string, string> }) => {
        calls.push(init?.headers);
        return { data: answer(init?.headers) };
      }
    })
  };
});

function Shown() {
  const set = usePermissions();
  return <p>{set ? set.permissions.join(",") : "reading"}</p>;
}

function renderProvider() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PermissionsProvider>
        <Shown />
      </PermissionsProvider>
    </QueryClientProvider>
  );
}

const SET = { policyClass: "OWN", permissions: ["del.note.dispatch"], activeScope: null };

describe("the session read", () => {
  beforeEach(() => {
    calls.length = 0;
    window.localStorage.clear();
  });
  afterEach(cleanup);

  it("names the entity for a user who holds it", async () => {
    answer = () => SET;
    renderProvider();

    await waitFor(() => expect(screen.getByText("del.note.dispatch")).toBeTruthy());
    expect(calls).toEqual([undefined]);
  });

  it("asks a user who holds one place only with no scope, and remembers it so the refusal (400) is met once", async () => {
    answer = (headers) => {
      if (!headers?.[SCOPE_UNNAMED]) {
        throw new ApiProblem({ code: "scope.invalid" } as never);
      }
      return SET;
    };
    renderProvider();
    await waitFor(() => expect(screen.getByText("del.note.dispatch")).toBeTruthy());
    expect(calls).toEqual([undefined, { [SCOPE_UNNAMED]: "1" }]);
    cleanup();

    calls.length = 0;
    renderProvider();
    await waitFor(() => expect(screen.getByText("del.note.dispatch")).toBeTruthy());
    expect(calls).toEqual([{ [SCOPE_UNNAMED]: "1" }]);
  });
});
