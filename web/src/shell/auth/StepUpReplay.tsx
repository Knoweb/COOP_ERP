import { useEffect, useState } from "react";
import { useIntl } from "react-intl";
import { useQueryClient } from "@tanstack/react-query";
import { replayPendingCommand, type ReplayOutcome } from "../api/pendingCommand";
import { useT } from "../i18n/useT";
import { useScope } from "../scope/useScope";
import { takePendingCommand } from "./oidc";
import { useSession } from "./session";

type ReplayState = { phase: "none" } | { phase: "replaying" } | { phase: "finished"; outcome: ReplayOutcome };

/**
 * Takes again, once, the command a step-up interrupted (shell/api/pendingCommand.ts). It sits
 * in the frame of every page, right under the header, so that the person who confirmed
 * "Suspend" and was sent to sign in again sees, on return, that the suspension went through,
 * or the server's own words when it did not. A page that shows the document re-reads it: every
 * query is invalidated when the replay is over.
 *
 * It shows nothing at all when no step-up brought a command back, which is nearly always.
 */
export function StepUpReplay() {
  const t = useT();
  const { locale } = useIntl();
  const session = useSession();
  const scope = useScope();
  const queryClient = useQueryClient();
  const [state, setState] = useState<ReplayState>({ phase: "none" });

  const accessToken = session?.accessToken;
  const entityId = session?.entityId ?? null;
  const locationId = scope.locationId;

  useEffect(() => {
    if (!accessToken) {
      return;
    }
    // takePendingCommand() empties the hand-over, so a second run of this effect (React's
    // strict mode, a token renewal) finds nothing and replays nothing.
    const pending = takePendingCommand();
    if (!pending) {
      return;
    }
    let cancelled = false;
    setState({ phase: "replaying" });
    replayPendingCommand(pending, { accessToken, locale, session: { entityId }, locationId })
      .then((outcome) => {
        if (!cancelled) {
          setState({ phase: "finished", outcome });
        }
      })
      .finally(() => void queryClient.invalidateQueries());
    return () => {
      cancelled = true;
    };
  }, [accessToken, entityId, locale, locationId, queryClient]);

  if (state.phase === "none") {
    return null;
  }
  if (state.phase === "replaying") {
    return (
      <p className="step-up-replay" role="status" aria-busy="true">
        {t("shell.stepup.replaying").text}
      </p>
    );
  }
  if (state.outcome.ok) {
    return (
      <p className="step-up-replay" role="status">
        {t("shell.stepup.done").text}
      </p>
    );
  }
  // The server's title is already in the user's language; a response that is not from our
  // API (code "unknown") has none, and then the shell's own sentence is shown.
  return (
    <p className="step-up-replay" role="alert">
      {state.outcome.problem.title || t("shell.stepup.failed").text}
    </p>
  );
}
