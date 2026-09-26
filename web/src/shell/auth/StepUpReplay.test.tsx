import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { IntlProvider } from "react-intl";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { PendingCommand, ReplayOutcome } from "../api/pendingCommand";
import { messages } from "../i18n/messages";
import { ScopeProvider } from "../scope/ScopeContext";
import { StepUpReplay } from "./StepUpReplay";

const session = {
  userId: "u-1", displayName: "Officer", entityId: "0190f000-0000-7000-8000-000000000001",
  policyClass: "OWN" as const, language: "en" as const, roles: ["fed-admin"], accessToken: "fresh", signOut: () => {}
};
vi.mock("./session", () => ({ useSession: () => session }));

let broughtBack: PendingCommand | null = null;
vi.mock("./oidc", () => ({
  takePendingCommand: () => {
    const pending = broughtBack;
    broughtBack = null;
    return pending;
  }
}));

const replay = vi.fn<(...args: unknown[]) => Promise<ReplayOutcome>>();
vi.mock("../api/pendingCommand", () => ({ replayPendingCommand: (...args: unknown[]) => replay(...args) }));

const pending: PendingCommand = { method: "POST", url: "http://api.test/v1/party/entities/e-1/suspend", headers: { "Idempotency-Key": "k" }, body: "{}" };

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
    replay.mockReset();
  });
  afterEach(cleanup);

  it("shows nothing when no sign-in brought a command back", () => {
    renderReplay();
    expect(document.body.querySelector(".step-up-replay")).toBeNull();
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
