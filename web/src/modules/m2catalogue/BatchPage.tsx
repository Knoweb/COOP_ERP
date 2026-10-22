import { useState } from "react";
import type { FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { useFormatDate } from "../../shell/i18n/formats";
import { MoneyDisplay } from "../../shell/components/MoneyDisplay";
import { StateChip, type ChipState } from "../../shell/components/StateChip";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useHasPermission } from "../../shell/auth/permissions";
import { ApiProblem } from "../../shell/api/client";
import { useCatalogueApi, type Batch } from "./catalogueApi";
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
        <p role="alert">{errorText(batchQuery.error, t("catalogue.error.not_found").text)}</p>
        <Link className="back-link" to="/catalogue">
          <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
          {t("catalogue.back").text}
        </Link>
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
      <Link className="back-link" to={`/catalogue/skus/${batch.skuId}`}>
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("catalogue.batch.back_to_sku").text}
      </Link>

      <div className="catalogue-header-row">
        <h1>{batch.batchNo}</h1>
        <StateChip state={chipState} label={t(`catalogue.batch.status.${batch.status}`).text} />
      </div>

      {batch.status === "SUPERSEDED" && (
        <div className="import-alert import-alert--warning" role="status">
          <span>{t("catalogue.batch.superseded_notice").text}</span>
        </div>
      )}

      {batch.correctsBatchId && (
        <div className="batch-chain-notice">
          <span>
            {t("catalogue.batch.corrects_chain", undefined, { batchNo: batch.correctsBatchId.slice(0, 8) }).text}
          </span>{" "}
          <Link to={`/catalogue/batches/${batch.correctsBatchId}`}>
            ({batch.correctsBatchId})
          </Link>
        </div>
      )}

      {replacementBatch && (
        <div className="import-alert import-alert--success" role="status">
          <span>{t("catalogue.batch.success.corrected").text} </span>
          <Link to={`/catalogue/batches/${replacementBatch.batchId}`}>
            {replacementBatch.batchNo} ({replacementBatch.batchId.slice(0, 8)})
          </Link>
        </div>
      )}

      {/* Details Card */}
      <section className="catalogue-section">
        <h2>{t("catalogue.batch.title").text}</h2>
        <div className="batch-details-grid">
          <div className="batch-detail-item">
            <span className="batch-detail-label">{t("catalogue.column.batch_no").text}</span>
            <span className="batch-detail-value">{batch.batchNo}</span>
          </div>
          <div className="batch-detail-item">
            <span className="batch-detail-label">{t("catalogue.column.mrp").text}</span>
            <span className="batch-detail-value batch-detail-value--mrp">
              {batch.printedMrp != null ? <MoneyDisplay amount={batch.printedMrp} /> : "—"}
            </span>
          </div>
          <div className="batch-detail-item">
            <span className="batch-detail-label">{t("catalogue.column.expiry").text}</span>
            <span className="batch-detail-value">
              {batch.expiryDate ? formatDate(batch.expiryDate) : "—"}
            </span>
          </div>
          <div className="batch-detail-item">
            <span className="batch-detail-label">{t("catalogue.column.from").text}</span>
            <span className="batch-detail-value">
              {batch.manufactureDate ? formatDate(batch.manufactureDate) : "—"}
            </span>
          </div>
          <div className="batch-detail-item">
            <span className="batch-detail-label">{t("catalogue.column.status").text}</span>
            <span className="batch-detail-value">{t(`catalogue.batch.status.${batch.status}`).text}</span>
          </div>
        </div>
      </section>

      {/* Correction Form / Action */}
      {batch.status === "REGISTERED" && canCorrect && (
        <section className="catalogue-section">
          {!isCorrecting ? (
            <button
              type="button"
              className="action-button action-button--primary"
              onClick={handleStartCorrection}
            >
              {t("catalogue.batch.correct").text}
            </button>
          ) : (
            <form onSubmit={handleCorrectSubmit} className="batch-correction-form">
              <h3>{t("catalogue.batch.correct.title").text}</h3>
              <label className="catalogue-form-field">
                {t("catalogue.batch.field.mrp").text}
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
                {t("catalogue.batch.field.expiry").text}
                <input
                  type="date"
                  value={newExpiry}
                  onChange={(e) => setNewExpiry(e.target.value)}
                />
              </label>

              <label className="catalogue-form-field">
                {t("shell.reason.code").text}
                <select value={reasonCode} onChange={(e) => setReasonCode(e.target.value)}>
                  <option value="MISKEYED_MRP">{t("catalogue.batch.reason.miskeyed").text}</option>
                  <option value="SUPPLIER_AMENDMENT">{t("catalogue.batch.reason.supplier_amendment").text}</option>
                  <option value="LABEL_ERROR">{t("catalogue.batch.reason.label_error").text}</option>
                  <option value="OTHER">{t("catalogue.reason.other").text}</option>
                </select>
              </label>

              <label className="catalogue-form-field">
                {t("shell.reason.text").text || "Explanation"}
                <textarea
                  rows={2}
                  value={reasonText}
                  onChange={(e) => setReasonText(e.target.value)}
                />
              </label>

              <div className="catalogue-action-bar">
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
          )}
        </section>
      )}
    </main>
  );
}
