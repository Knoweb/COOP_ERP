import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { type ChipState } from "../../shell/components/StateChip";
import { DocumentHeader } from "../../shell/components/DocumentHeader";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApiProblem } from "../../shell/api/client";
import { useCatalogueApi, type Batch } from "./catalogueApi";
import { CatalogueDropdown } from "./CatalogueDropdown";
import { errorText } from "./skuView";
import "./catalogue.css";

/**
 * Batch card with correction functionality (22A section 8, "Batch card").
 * Shows batch details, corrects/superseded chain, and allows correcting mis-keyed MRP or expiry.
 */
export function BatchPage() {
  const { batchId = "" } = useParams();
  const t = useT();
  const formatDate = useFormatDate();
  const api = useCatalogueApi();
  const queryClient = useQueryClient();
  const canCorrect = useHasPermission("cat.batch.correct");
  const correctKey = useIdempotencyKey();

  const batchQuery = useQuery({
    queryKey: ["catalogue", "batch", batchId],
    queryFn: () => api.getBatch(batchId)
  });

  const [isCorrecting, setIsCorrecting] = useState(false);
  const [newMrp, setNewMrp] = useState("");
  const [newExpiry, setNewExpiry] = useState("");
  const [reasonCode, setReasonCode] = useState("MISKEYED_MRP");
  const [reasonText, setReasonText] = useState("");
  const [replacementBatch, setReplacementBatch] = useState<Batch | null>(null);

  const correctMutation = useMutation({
    mutationFn: () =>
      api.correctBatch(
        batchId,
        {
          printedMrp: newMrp ? parseFloat(newMrp) : undefined,
          expiryDate: newExpiry || undefined,
          reasonCode,
          reasonText: reasonText.trim() || undefined
        },
        correctKey.current()
      ),
    onSuccess: (replacement) => {
      correctKey.next();
      setReplacementBatch(replacement);
      setIsCorrecting(false);
      queryClient.invalidateQueries({ queryKey: ["catalogue", "batch", batchId] });
      queryClient.invalidateQueries({ queryKey: ["catalogue", "batches"] });
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        correctKey.next();
      }
    }
  });

  if (batchQuery.isLoading) {
    return <main className="shell-page">{t("catalogue.loading").text}</main>;
  }

  if (batchQuery.isError || !batchQuery.data) {
    return (
      <main className="shell-page">
        <div style={{ marginBottom: "var(--space-2)" }}>
          <Link className="back-pill-btn" to="/catalogue">
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
            {t("catalogue.back").text}
          </Link>
        </div>
        <p role="alert">{errorText(batchQuery.error, t("catalogue.error.not_found").text)}</p>
      </main>
    );
  }

  const batch = batchQuery.data;
  const chipState: ChipState = batch.status === "REGISTERED" ? "issued" : "void";

  const handleStartCorrection = () => {
    setNewMrp(batch.printedMrp != null ? String(batch.printedMrp) : "");
    setNewExpiry(batch.expiryDate ?? "");
    setReasonCode("MISKEYED_MRP");
    setReasonText("");
    setIsCorrecting(true);
  };

  const handleCorrectSubmit = (e: FormEvent) => {
    e.preventDefault();
    correctMutation.mutate();
  };

  return (
    <main className="shell-page">
      <div style={{ marginBottom: "var(--space-2)" }}>
        <Link className="back-pill-btn" to={`/catalogue/skus/${batch.skuId}`}>
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
          {t("catalogue.batch.back_to_sku").text}
        </Link>
      </div>

      {batch.status === "SUPERSEDED" && (
        <div className="import-alert import-alert--warning" role="status" style={{ marginBottom: "var(--space-2)" }}>
          <span>{t("catalogue.batch.superseded_notice").text}</span>
        </div>
      )}

      {batch.correctsBatchId && (
        <div className="batch-chain-notice" style={{ marginBottom: "var(--space-2)" }}>
          <span>
            {t("catalogue.batch.corrects_chain", undefined, { batchNo: batch.correctsBatchId.slice(0, 8) }).text}
          </span>{" "}
          <Link to={`/catalogue/batches/${batch.correctsBatchId}`}>
            ({batch.correctsBatchId})
          </Link>
        </div>
      )}

      {replacementBatch && (
        <div className="import-alert import-alert--success" role="status" style={{ marginBottom: "var(--space-2)" }}>
          <span>{t("catalogue.batch.success.corrected").text} </span>
          <Link to={`/catalogue/batches/${replacementBatch.batchId}`}>
            {replacementBatch.batchNo} ({replacementBatch.batchId.slice(0, 8)})
          </Link>
        </div>
      )}

      {/* Main Details Card matching SocietyCardPage (DocumentHeader) */}
      <DocumentHeader
        code="BATCH"
        title={batch.batchNo}
        subtitle={t("catalogue.batch.title").text}
        state={{ look: chipState, label: t(`catalogue.batch.status.${batch.status}`).text }}
        facts={[
          { label: t("catalogue.column.batch_no").text, value: batch.batchNo },
          {
            label: t("catalogue.column.mrp").text,
            value: batch.printedMrp != null ? <MoneyDisplay amount={batch.printedMrp} /> : undefined
          },
          {
            label: t("catalogue.column.expiry").text,
            value: batch.expiryDate ? formatDate(batch.expiryDate) : undefined
          },
          {
            label: t("catalogue.column.from").text,
            value: batch.manufactureDate ? formatDate(batch.manufactureDate) : undefined
          },
          {
            label: t("catalogue.column.status").text,
            value: t(`catalogue.batch.status.${batch.status}`).text
          }
        ]}
      >
        {batch.status === "REGISTERED" && canCorrect && !isCorrecting && (
          <div className="batch-card-actions" style={{ paddingTop: "var(--space-2)" }}>
            <button
              type="button"
              className="action-btn--correct-batch"
              onClick={handleStartCorrection}
            >
              {t("catalogue.batch.correct").text}
            </button>
          </div>
        )}
      </DocumentHeader>

      {/* Correction Form Card */}
      {batch.status === "REGISTERED" && canCorrect && isCorrecting && (
        <div className="batch-correction-card">
          <form onSubmit={handleCorrectSubmit} className="batch-correction-form">
            <h3 className="batch-correction-title">{t("catalogue.batch.correct.title").text}</h3>
            <div className="batch-correction-grid">
              <label className="catalogue-form-field">
                <span>{t("catalogue.batch.field.mrp").text}</span>
                <input
                  type="number"
                  step="0.01"
                  min="0.01"
                  required
                  value={newMrp}
                  onChange={(e) => setNewMrp(e.target.value)}
                />
              </label>

              <label className="catalogue-form-field">
                <span>{t("catalogue.batch.field.expiry").text}</span>
                <input
                  type="date"
                  value={newExpiry}
                  onChange={(e) => setNewExpiry(e.target.value)}
                />
              </label>

              <CatalogueDropdown
                label={t("shell.reason.code").text}
                value={reasonCode}
                onChange={(val) => setReasonCode(val)}
                options={[
                  { value: "MISKEYED_MRP", label: t("catalogue.batch.reason.miskeyed").text },
                  { value: "SUPPLIER_AMENDMENT", label: t("catalogue.batch.reason.supplier_amendment").text },
                  { value: "LABEL_ERROR", label: t("catalogue.batch.reason.label_error").text },
                  { value: "OTHER", label: t("catalogue.reason.other").text }
                ]}
              />

              <label className="catalogue-form-field" style={{ gridColumn: "1 / -1" }}>
                <span>{t("shell.reason.text").text}</span>
                <textarea
                  rows={2}
                  value={reasonText}
                  onChange={(e) => setReasonText(e.target.value)}
                />
              </label>
            </div>

            <div className="catalogue-action-bar" style={{ marginTop: "var(--space-2)" }}>
              <button
                type="submit"
                className="action-button action-button--primary"
                disabled={correctMutation.isPending || !newMrp}
              >
                {t("catalogue.batch.correct.submit").text}
              </button>
              <button
                type="button"
                className="action-button action-button--secondary"
                onClick={() => setIsCorrecting(false)}
                disabled={correctMutation.isPending}
              >
                {t("shell.reason.cancel").text || "Cancel"}
              </button>
            </div>

            {correctMutation.isError && (
              <p role="alert" className="error-text">
                {errorText(correctMutation.error, t("catalogue.error.generic").text)}
              </p>
            )}
          </form>
        </div>
      )}
    </main>
  );
}
