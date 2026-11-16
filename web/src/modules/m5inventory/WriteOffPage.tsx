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
import { PageHeader } from "../../shell/components/PageHeader";
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
      <div>
        <Link className="back-pill-btn" to="/inventory/write-offs">
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            stroke="currentColor"
            strokeWidth="2"
            fill="none"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="m12 19-7-7 7-7" />
            <path d="M19 12H5" />
          </svg>
          {t("inventory.writeoff.back").text}
        </Link>
      </div>
      <PageHeader icon="writeoffs" title={t("inventory.writeoff.doc_title").text} />

      <section className="modern-table-card writeoff-summary-card">
        <div className="writeoff-summary-card__header">
          <div className="writeoff-summary-card__identity">
            <span className="writeoff-summary-card__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
                <line x1="9" y1="15" x2="15" y2="15" />
              </svg>
            </span>
            <span className="writeoff-doc-no">
              {w.documentNo ? `${t("inventory.writeoff.document").text}: ${w.documentNo}` : t("inventory.writeoff.doc_title").text}
            </span>
          </div>
          <StateChip state={writeOffChip(w.status)} label={t(`inventory.writeoff.status.${w.status}`).text} />
        </div>

        <div className="writeoff-meta-grid">
          <div className="writeoff-meta-item">
            <span className="writeoff-meta-label">{t("inventory.writeoff.category").text}</span>
            <span className="writeoff-meta-value writeoff-category-tag">
              {t(`inventory.writeoff.category.${w.category}`).text}
            </span>
          </div>

          {w.value != null && (
            <div className="writeoff-meta-item">
              <span className="writeoff-meta-label">{t("inventory.writeoff.value").text}</span>
              <span className="writeoff-meta-value">
                <MoneyDisplay amount={w.value} />
                {w.band != null && (
                  <span className="writeoff-band-badge">
                    {t("inventory.count.band", undefined, { band: w.band }).text}
                  </span>
                )}
              </span>
            </div>
          )}

          {w.witnessedAt && (
            <div className="writeoff-meta-item">
              <span className="writeoff-meta-label">{t("inventory.writeoff.witness").text}</span>
              <span className="writeoff-meta-value writeoff-witness-date">
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
                  <circle cx="12" cy="12" r="10" />
                  <polyline points="12 6 12 12 16 14" />
                </svg>
                <span>{formatInstant(w.witnessedAt)}</span>
              </span>
            </div>
          )}

          {w.remoteWitness && (
            <div className="writeoff-meta-item">
              <span className="writeoff-meta-label">{t("inventory.writeoff.remote").text}</span>
              <span className="writeoff-meta-value">{t("inventory.writeoff.remote").text}</span>
            </div>
          )}
        </div>

        {w.note && (
          <div className="writeoff-note-callout">
            <span className="writeoff-note-callout__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
              </svg>
            </span>
            <p className="writeoff-note-callout__text">{w.note}</p>
          </div>
        )}

        {w.rejectReason && (
          <div className="writeoff-reject-callout" role="alert">
            <span className="writeoff-reject-callout__icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
                <circle cx="12" cy="12" r="10" />
                <line x1="12" y1="8" x2="12" y2="12" />
                <line x1="12" y1="16" x2="12.01" y2="16" />
              </svg>
            </span>
            <p className="writeoff-reject-callout__text">{w.rejectReason}</p>
          </div>
        )}
      </section>

      <section className="modern-table-card writeoff-lines-card">
        <div className="inventory-card-header">
          <span className="inventory-card-header__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
              <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
              <rect x="8" y="2" width="8" height="4" rx="1" ry="1" />
              <line x1="8" y1="11" x2="16" y2="11" />
              <line x1="8" y1="15" x2="16" y2="15" />
            </svg>
          </span>
          <h2 className="inventory-card-header__title">{t("inventory.column.item").text}</h2>
        </div>
        <div className="modern-table-scroll">
          <table className="modern-table stock-table writeoff-table">
            <thead>
              <tr>
                <th className="writeoff-col-item">{t("inventory.column.item").text}</th>
                <th className="writeoff-col-batch">{t("inventory.column.batch").text}</th>
                <th className="writeoff-col-condition">{t("inventory.column.condition").text}</th>
                <th className="writeoff-col-qty numeric-cell">{t("inventory.column.qty").text}</th>
              </tr>
            </thead>
            <tbody>
              {w.lines.map((line) => (
                <tr key={line.lineNo}>
                  <td className="writeoff-col-item col-item" data-label={t("inventory.column.item").text}>
                    <Link to={`/inventory/locations/${w.locationId}/skus/${line.skuId}`} className="writeoff-sku-link">
                      <SkuLabel skuId={line.skuId} />
                    </Link>
                  </td>
                  <td className="writeoff-col-batch" data-label={t("inventory.column.batch").text}>
                    {isSyntheticBatchNo(line.batchNo) ? t("inventory.field.batch_not_tracked").text : (line.batchNo ?? "")}
                  </td>
                  <td className="writeoff-col-condition" data-label={t("inventory.column.condition").text}>
                    <span className="writeoff-condition-tag">{t(`inventory.condition.${line.condition}`).text}</span>
                  </td>
                  <td className="writeoff-col-qty numeric-cell" data-label={t("inventory.column.qty").text}>
                    {line.qty}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="modern-table-card writeoff-photos-card">
        <div className="inventory-card-header">
          <span className="inventory-card-header__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" focusable="false">
              <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
              <circle cx="12" cy="13" r="4" />
            </svg>
          </span>
          <h2 className="inventory-card-header__title">{t("inventory.writeoff.photos").text}</h2>
        </div>
        <div className="writeoff-photos-body">
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
        </div>
      </section>

      {next && (
        <div className="writeoff-next-banner">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
            <circle cx="12" cy="12" r="10" />
            <polyline points="12 16 16 12 12 8" />
            <line x1="8" y1="12" x2="16" y2="12" />
          </svg>
          <span>{t(`inventory.writeoff.next.${next}`).text}</span>
        </div>
      )}
      <div className="writeoff-approval-actions">
        <ApprovalBar actions={actions} />
      </div>
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
