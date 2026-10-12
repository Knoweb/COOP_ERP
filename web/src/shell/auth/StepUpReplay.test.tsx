import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PendingCommand, ReplayContext, ReplayOutcome } from "../api/pendingCommand";
import { messages } from "../i18n/messages";
import { ScopeProvider } from "../scope/ScopeContext";
import { StepUpReplay } from "./StepUpReplay";

const session = {
  userId: "u-1", displayName: "Officer", entityId: "0190f000-0000-7000-8000-000000000001",
  policyClass: "OWN" as const, language: "en" as const, accessToken: "fresh", signOut: () => {}
};
vi.mock("./session", () => ({ useSession: () => session }));

let broughtBack: PendingCommand | null = null;
let notKept = false;
vi.mock("./oidc", () => ({
  takeCommandNotKept: () => {
    const flag = notKept;
    notKept = false;
    return flag;
  },
  takePendingCommand: () => {
    const pending = broughtBack;
    broughtBack = null;
    return pending;
  }
}));

const replay = vi.fn<(...args: unknown[]) => Promise<ReplayOutcome>>();
vi.mock("../api/pendingCommand", () => ({ replayPendingCommand: (...args: unknown[]) => replay(...args) }));

const pending: PendingCommand = {
  method: "POST",
  url: "http://api.test/v1/party/entities/e-1/suspend",
  headers: { "Idempotency-Key": "k" },
  body: "{}",
  subject: session.userId
};

function renderReplay() {
  const queryClient = new QueryClient();
  const invalidate = vi.spyOn(queryClient, "invalidateQueries");
  render(
    <IntlProvider locale="en" messages={messages.en}>
      <QueryClientProvider client={queryClient}>
        <ScopeProvider>
          <StepUpReplay />
        </ScopeProvider>
      </QueryClientProvider>
    </IntlProvider>
  );
  return { invalidate };
}

describe("the replay after a step-up", () => {
  beforeEach(() => {
    broughtBack = null;
    notKept = false;
    replay.mockReset();
  });
  afterEach(cleanup);

  it("shows nothing when no sign-in brought a command back", () => {
    renderReplay();
    expect(document.body.querySelector(".step-up-replay")).toBeNull();
    expect(replay).not.toHaveBeenCalled();
  });

  it("asks for the details again when the command that was cut off was not kept, and replays nothing", async () => {
    notKept = true;
    renderReplay();

    expect((await screen.findByRole("status")).textContent).toBe("Your sign-in was refreshed. Enter the details again.");
    expect(replay).not.toHaveBeenCalled();
  });

  it("takes the command again once with the fresh token and the scope, says so, and makes every page re-read", async () => {
    broughtBack = pending;
    replay.mockResolvedValue({ ok: true });
    const { invalidate } = renderReplay();

    expect(screen.getByRole("status").textContent).toContain("Finishing the action");
    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("is done"));

    expect(replay).toHaveBeenCalledTimes(1);
    expect(replay).toHaveBeenCalledWith(pending, { accessToken: "fresh", locale: "en", session: { entityId: session.entityId }, locationId: null });
    expect(invalidate).toHaveBeenCalled();
  });

  it("replays nothing and asks for the details again when someone else completed the sign-in", async () => {
    // User A was interrupted on a shared PC; user B typed their own name on the sign-in page.
    broughtBack = { ...pending, subject: "u-somebody-else" };
    renderReplay();

    expect((await screen.findByRole("status")).textContent).toBe("Your sign-in was refreshed. Enter the details again.");
    expect(replay).not.toHaveBeenCalled();
  });

  it("replays nothing for a command that does not say whose it is", async () => {
    broughtBack = { ...pending, subject: undefined };
    renderReplay();

    expect((await screen.findByRole("status")).textContent).toBe("Your sign-in was refreshed. Enter the details again.");
    expect(replay).not.toHaveBeenCalled();
  });

  it("sends a one-location user's command to the location it was sent in, though the session read has not named it yet", async () => {
    // The shop staff user: the scope is one shop. On the first render after the sign-in the
    // permission read has not answered, so the shell's own location is still null.
    const actual = await vi.importActual<typeof import("../api/pendingCommand")>("../api/pendingCommand");
    const sent: Headers[] = [];
    const fetchFn = vi.fn(async (_url: string, init: RequestInit) => {
      sent.push(init.headers as Headers);
      return new Response(null, { status: 204 });
    });
    replay.mockImplementation((p, c) => actual.replayPendingCommand(p as PendingCommand, c as ReplayContext, fetchFn as unknown as typeof fetch));
    broughtBack = {
      ...pending,
      headers: { "Idempotency-Key": "k", "X-Scope-Entity": session.entityId, "X-Scope-Location": "0190f000-0000-7000-8000-0000000000c1" }
    };
    renderReplay();

    await waitFor(() => expect(screen.getByRole("status").textContent).toContain("is done"));
    expect(replay).toHaveBeenCalledTimes(1);
    expect((replay.mock.calls[0][1] as ReplayContext).locationId).toBeNull();
    expect(sent[0].get("X-Scope-Entity")).toBe(session.entityId);
    expect(sent[0].get("X-Scope-Location")).toBe("0190f000-0000-7000-8000-0000000000c1");
  });

  it("shows the server's own words when the replay is refused", async () => {
    broughtBack = pending;
    replay.mockResolvedValue({ ok: false, problem: { status: 422, code: "m1.entity.status", title: "The society is not active" } });
    renderReplay();

    await waitFor(() => expect(screen.getByRole("alert").textContent).toBe("The society is not active"));
  });

  it("falls back to its own sentence when the refusal came from no API of ours", async () => {
    broughtBack = pending;
    replay.mockResolvedValue({ ok: false, problem: { status: 502, code: "unknown" } });
    renderReplay();

    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("did not go through"));
  });
});
