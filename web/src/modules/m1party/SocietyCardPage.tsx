import { useState } from "react";
import { useIntl } from "react-intl";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApprovalBar } from "../../shell/components/ApprovalBar";
import type { ApprovalAction } from "../../shell/components/ApprovalBar";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { usePartyApi } from "./partyApi";
import type { ReasonRequest, Society } from "./partyApi";
import { REINSTATE_REASONS, STATUS_LOOK, SUSPEND_REASONS, legalNameIn, statusMessageId } from "./societyView";
import { errorText } from "./SocietyRegisterPage";

type PendingReason = "suspend" | "reinstate" | null;

/**
 * One society's card (21A section 8): the header with its code, name, status and facts, and
 * the commands its status allows. ONBOARDING offers Activate; ACTIVE offers Suspend;
 * SUSPENDED offers Reinstate. A command the status does not allow is not shown; a command the
 * status allows but the society is not ready for (no responsible officer, no VAT number) is
 * shown disabled WITH THE REASON, so the clerk knows what to complete first. The server
 * checks all of it again and its refusal is shown as it arrives.
 *
 * Suspend and reinstate take a reason (21A: it goes into the audit record), captured by the
 * shell's ReasonCapture before the command runs. Activate takes none.
 */
export function SocietyCardPage() {
  const t = useT();
  const { locale } = useIntl();
  const formatDate = useFormatDate();
  const { entityId = "" } = useParams();
  const queryClient = useQueryClient();
  const api = usePartyApi();
  const idempotencyKey = useIdempotencyKey();
  const canActivate = useHasPermission("gov.entity.activate");
  const canSuspend = useHasPermission("gov.entity.suspend");

  const [pendingReason, setPendingReason] = useState<PendingReason>(null);
  const [done, setDone] = useState<string | null>(null);

  const society = useQuery({
    queryKey: ["party", "society", entityId],
    queryFn: () => api.getSociety(entityId),
    enabled: entityId !== ""
  });

  const afterCommand = (messageId: string) => {
    idempotencyKey.next();
    setPendingReason(null);
    setDone(messageId);
    queryClient.invalidateQueries({ queryKey: ["party", "society", entityId] });
    queryClient.invalidateQueries({ queryKey: ["party", "societies"] });
  };
  const afterProblem = (error: unknown) => {
    if (error instanceof ApiProblem) {
      idempotencyKey.next();
    }
  };

  const activate = useMutation({
    mutationFn: () => api.activateSociety(entityId, idempotencyKey.current()),
    onSuccess: () => afterCommand("party.card.activated"),
    onError: afterProblem
  });
  const suspend = useMutation({
    mutationFn: (reason: ReasonRequest) => api.suspendSociety(entityId, reason, idempotencyKey.current()),
    onSuccess: () => afterCommand("party.card.suspended"),
    onError: afterProblem
  });
  const reinstate = useMutation({
    mutationFn: (reason: ReasonRequest) => api.reinstateSociety(entityId, reason, idempotencyKey.current()),
    onSuccess: () => afterCommand("party.card.reinstated"),
    onError: afterProblem
  });

  const commandError = activate.error ?? suspend.error ?? reinstate.error;

  if (society.isLoading) {
    return (
      <main className="shell-page">
        <p>{t("party.list.loading").text}</p>
      </main>
    );
  }
  if (society.isError || !society.data) {
    return (
      <main className="shell-page">
        <p>
          <Link to="/party/societies">{t("party.back_to_register").text}</Link>
        </p>
        <p role="alert">{errorText(society.error, t("party.error.generic").text)}</p>
      </main>
    );
  }

  const data = society.data;
  const actions = actionsFor(data, {
    canActivate,
    canSuspend,
    t: (id) => t(id).text,
    busy: activate.isPending || suspend.isPending || reinstate.isPending,
    onActivate: () => {
      setDone(null);
      activate.mutate();
    },
    onSuspend: () => {
      setDone(null);
      setPendingReason("suspend");
    },
    onReinstate: () => {
      setDone(null);
      setPendingReason("reinstate");
    }
  });

  return (
    <main className="shell-page">
      <p>
        <Link to="/party/societies">{t("party.back_to_register").text}</Link>
      </p>

      <DocumentHeader
        code={data.entityCode ?? ""}
        title={legalNameIn(data, locale) ?? data.legalNameEn}
        subtitle={data.entityType ? t(`party.type.${data.entityType}`).text : undefined}
        state={data.status ? { look: STATUS_LOOK[data.status], label: t(statusMessageId(data.status)).text } : undefined}
        facts={[
          { label: t("party.field.registration_no").text, value: data.registrationNo },
          { label: t("party.field.vat_no").text, value: data.vatRegistrationNo },
          { label: t("party.field.district").text, value: data.district },
          { label: t("party.field.language").text, value: data.defaultLanguage ? t(`party.language.${data.defaultLanguage}`).text : undefined },
          { label: t("party.field.fy_start").text, value: data.financialYearStartMonth ? t(`party.month.${data.financialYearStartMonth}`).text : undefined },
          { label: t("party.card.officer").text, value: data.responsibleOfficerUserId },
          { label: t("party.card.governance_signed").text, value: data.dataGovernanceSignedOn ? formatDate(data.dataGovernanceSignedOn) : undefined }
        ]}
      >
        <ApprovalBar actions={actions} />
      </DocumentHeader>

      {pendingReason === "suspend" && (
        <ReasonCapture
          title={t("party.card.suspend.question").text}
          codes={SUSPEND_REASONS.map((code) => ({ code, label: t(`party.reason.${code}`).text }))}
          onConfirm={(reasonCode, reasonText) => suspend.mutate({ reasonCode, reasonText })}
          onCancel={() => setPendingReason(null)}
          pending={suspend.isPending}
        />
      )}
      {pendingReason === "reinstate" && (
        <ReasonCapture
          title={t("party.card.reinstate.question").text}
          codes={REINSTATE_REASONS.map((code) => ({ code, label: t(`party.reason.${code}`).text }))}
          onConfirm={(reasonCode, reasonText) => reinstate.mutate({ reasonCode, reasonText })}
          onCancel={() => setPendingReason(null)}
          pending={reinstate.isPending}
        />
      )}

      {done && <p role="status">{t(done).text}</p>}
      {commandError !== null && <p role="alert">{errorText(commandError, t("party.error.generic").text)}</p>}
    </main>
  );
}

/**
 * The actions of the bar for a society in its status. Pure, so the tests can call it: what
 * shows, what is disabled and why, for every status and permission.
 */
export function actionsFor(
  society: Society,
  wiring: {
    canActivate: boolean;
    canSuspend: boolean;
    t: (id: string) => string;
    busy: boolean;
    onActivate: () => void;
    onSuspend: () => void;
    onReinstate: () => void;
  }
): ApprovalAction[] {
  const { t } = wiring;
  switch (society.status) {
    case "ONBOARDING": {
      if (!wiring.canActivate) {
        return [];
      }
      // The prerequisites the card can see (21A section 6, ActivateEntity). The server also
      // asks for an active user with user management; that one only it can tell.
      const missing = !society.responsibleOfficerUserId
        ? t("party.card.activate.no_officer")
        : !society.vatRegistrationNo
          ? t("party.card.activate.no_vat")
          : undefined;
      return [
        {
          id: "activate",
          label: t("party.card.activate"),
          primary: true,
          disabledReason: missing,
          pending: wiring.busy,
          onClick: wiring.onActivate
        }
      ];
    }
    case "ACTIVE":
      return wiring.canSuspend
        ? [{ id: "suspend", label: t("party.card.suspend"), pending: wiring.busy, onClick: wiring.onSuspend }]
        : [];
    case "SUSPENDED":
      return wiring.canSuspend
        ? [{ id: "reinstate", label: t("party.card.reinstate"), primary: true, pending: wiring.busy, onClick: wiring.onReinstate }]
        : [];
    default:
      return [];
  }
}
