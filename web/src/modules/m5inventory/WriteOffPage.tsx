import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatInstant } from "../../shell/i18n/formats";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { useSession } from "../../shell/auth/session";
import { ApprovalBar, type ApprovalAction } from "../../shell/components/ApprovalBar";
import { AttachmentCapture } from "../../shell/components/AttachmentCapture";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { ReasonCapture } from "../../shell/components/ReasonCapture";
import { StateChip } from "../../shell/components/StateChip";
import { useInventoryApi } from "./inventoryApi";
import { SkuLabel } from "./SkuLabel";
import { nextWriteOffStep, photosMissing, writeOffChip } from "./stockControl";
import { errorText, isSyntheticBatchNo } from "./stockView";
import "./inventory.css";

/**
 * One write-off (25A section 8, "Damage/expiry register" and its approval): the category, the
 * lines, the photographs (required when the category or a single-staff location says so), who
 * must act next, and the bar of what the reader may do now: submit the draft, witness it (another
 * person than the requester), approve it within the band, or reject it with a reason.
 */
export function WriteOffPage() {
  const { writeOffId = "" } = useParams();
  const t = useT();
  const api = useInventoryApi();
  const queryClient = useQueryClient();
  const session = useSession();
  const formatInstant = useFormatInstant();
  const canRequest = useHasPermission("inv.writeoff.request");
  const canWitness = useHasPermission("inv.writeoff.witness");
  const canApprove = useHasPermission("inv.writeoff.approve");
  const stepKey = useIdempotencyKey();
  const photoKey = useIdempotencyKey();
  const rejectKey = useIdempotencyKey();
  const [rejecting, setRejecting] = useState(false);

  const writeOff = useQuery({
    queryKey: ["inventory", "write-off", writeOffId],
    queryFn: () => api.writeOff(writeOffId)
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ["inventory"] });
  const forget = (key: { next: () => void }) => (error: unknown) => {
    if (error instanceof ApiProblem) {
      key.next();
    }
  };
  const step = useMutation({
    mutationFn: (next: "submit" | "witness" | "approve") => api.writeOffStep(writeOffId, next, stepKey.current()),
    onSuccess: () => {
      stepKey.next();
      refresh();
    },
    onError: forget(stepKey)
  });
  const photo = useMutation({
    mutationFn: (file: File) => api.addWriteOffPhoto(writeOffId, file, photoKey.current()),
    onSuccess: () => {
      photoKey.next();
      refresh();
    },
    onError: forget(photoKey)
  });
  const reject = useMutation({
    mutationFn: (reason: string) => api.rejectWriteOff(writeOffId, reason, rejectKey.current()),
    onSuccess: () => {
      rejectKey.next();
      setRejecting(false);
      refresh();
    },
    onError: forget(rejectKey)
  });

  if (writeOff.isLoading) {
    return <main className="shell-page">{t("inventory.loading").text}</main>;
  }
  if (writeOff.isError || !writeOff.data) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(writeOff.error, t("inventory.error.generic").text)}</p>
      </main>
    );
  }

  const w = writeOff.data;
  const next = nextWriteOffStep(w.status);
  const mine = session?.userId !== undefined && session.userId === w.requestedBy;
  const actions: ApprovalAction[] = [];
  if (next === "submit" && canRequest) {
    actions.push({
      id: "submit",
      label: t("inventory.writeoff.submit").text,
      primary: true,
      pending: step.isPending,
      disabledReason: photosMissing(w) ? t("inventory.writeoff.photos_missing").text : undefined,
      onClick: () => step.mutate("submit")
    });
  }
  if (next === "witness" && canWitness) {
    actions.push({
      id: "witness",
      label: t("inventory.writeoff.witness").text,
      primary: true,
      pending: step.isPending,
      disabledReason: mine ? t("inventory.writeoff.requester_cannot").text : undefined,
      onClick: () => step.mutate("witness")
    });
  }
  if (next === "approve" && canApprove) {
    actions.push({
      id: "approve",
      label: t("inventory.writeoff.approve").text,
      primary: true,
      pending: step.isPending,
      disabledReason: mine ? t("inventory.writeoff.requester_cannot").text : undefined,
      onClick: () => step.mutate("approve")
    });
  }
  if ((w.status === "REQUESTED" || w.status === "WITNESSED") && canApprove) {
    actions.push({
      id: "reject",
      label: t("inventory.writeoff.reject").text,
      pending: reject.isPending,
      onClick: () => setRejecting(true)
    });
  }

  return (
    <main className="shell-page">
      <Link className="back-link" to="/inventory/write-offs">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("inventory.writeoff.back").text}
      </Link>
      <h1>{t("inventory.writeoff.doc_title").text}</h1>
      <p>
        <StateChip state={writeOffChip(w.status)} label={t(`inventory.writeoff.status.${w.status}`).text} />
        {w.documentNo && ` ${t("inventory.writeoff.document").text}: ${w.documentNo}`}
      </p>
      <p>{`${t("inventory.writeoff.category").text}: ${t(`inventory.writeoff.category.${w.category}`).text}`}</p>
      {w.note && <p>{w.note}</p>}
      {w.value != null && (
        <p>
          {`${t("inventory.writeoff.value").text}: `}
          <MoneyDisplay amount={w.value} />
          {w.band != null && ` · ${t("inventory.count.band", undefined, { band: w.band }).text}`}
        </p>
      )}
      {w.remoteWitness && <p>{t("inventory.writeoff.remote").text}</p>}
      {w.witnessedAt && <p>{`${t("inventory.writeoff.witness").text}: ${formatInstant(w.witnessedAt)}`}</p>}
      {w.rejectReason && <p>{w.rejectReason}</p>}

      <table>
        <thead>
          <tr>
            <th>{t("inventory.column.item").text}</th>
            <th>{t("inventory.column.batch").text}</th>
            <th>{t("inventory.column.condition").text}</th>
            <th>{t("inventory.column.qty").text}</th>
          </tr>
        </thead>
        <tbody>
          {w.lines.map((line) => (
            <tr key={line.lineNo}>
              <td>
                <Link to={`/inventory/locations/${w.locationId}/skus/${line.skuId}`}>
                  <SkuLabel skuId={line.skuId} />
                </Link>
              </td>
              <td>{isSyntheticBatchNo(line.batchNo) ? t("inventory.field.batch_not_tracked").text : (line.batchNo ?? "")}</td>
              <td>{t(`inventory.condition.${line.condition}`).text}</td>
              <td className="numeric-cell">{line.qty}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>{t("inventory.writeoff.photos").text}</h2>
      <AttachmentCapture
        attachments={w.photos.map((p, index) => ({
          id: p.attachmentId,
          label: `${index + 1}. ${t(`inventory.writeoff.photo.status.${p.status}`).text}`
        }))}
        addLabel={t("inventory.writeoff.photo.add").text}
        emptyLabel={t("inventory.writeoff.photo.none").text}
        requiredLabel={w.photosRequired ? t("inventory.writeoff.photos_required").text : undefined}
        disabled={w.status !== "DRAFT" || !canRequest || photo.isPending}
        onAdd={(file) => photo.mutate(file)}
      />

      {next && <p>{t(`inventory.writeoff.next.${next}`).text}</p>}
      <ApprovalBar actions={actions} />
      {rejecting && (
        <ReasonCapture
          title={t("inventory.writeoff.reject.question").text}
          codes={["NOT_LOST", "WRONG_QTY", "OTHER"].map((code) => ({
            code,
            label: t(`inventory.writeoff.reason.${code}`).text
          }))}
          pending={reject.isPending}
          onCancel={() => setRejecting(false)}
          onConfirm={(code, text) =>
            reject.mutate([t(`inventory.writeoff.reason.${code}`).text, text].filter(Boolean).join(": "))
          }
        />
      )}
      {[step, photo, reject].map((m, i) =>
        m.isError ? (
          <p key={i} role="alert">
            {errorText(m.error, t("inventory.error.generic").text)}
          </p>
        ) : null
      )}
    </main>
  );
}
