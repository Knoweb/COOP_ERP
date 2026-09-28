import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { useT } from "../../shell/i18n/useT";
import { useTradingApi, type PaymentReceipt } from "./tradingApi";
import { businessToday, errorText, paymentReady, paymentRequest, type PaymentForm } from "./tradingView";

const METHODS: PaymentForm["method"][] = ["TRANSFER", "CASH", "CHEQUE", "DEPOSIT"];

/**
 * The receipt form of 24A section 8 ("Receipt book and matching"): the seller's accounts record a
 * payment received from a buyer, by cash, transfer, deposit or cheque (bank, number, date). From an
 * invoice (`invoiceId`) it settles that invoice and starts at its amount due; from the buyer's
 * account it settles the open invoices oldest first, and what is left stays on account.
 */
export function PaymentEntry({
  buyerEntityId,
  invoiceId,
  amountDue,
  onRecorded
}: {
  buyerEntityId: string;
  invoiceId?: string;
  amountDue?: number;
  onRecorded?: (receipt: PaymentReceipt) => void;
}) {
  const t = useT();
  const api = useTradingApi();
  const queryClient = useQueryClient();
  const key = useIdempotencyKey();
  const today = businessToday();
  const [form, setForm] = useState<PaymentForm>({
    method: "TRANSFER",
    amount: amountDue !== undefined ? String(amountDue) : "",
    reference: "",
    receivedOn: today,
    bank: "",
    chequeNo: "",
    chequeDated: today
  });
  const set = (patch: Partial<PaymentForm>) => setForm((current) => ({ ...current, ...patch }));
  const record = useMutation({
    mutationFn: () => api.recordPayment(paymentRequest(buyerEntityId, form, invoiceId), key.current()),
    onSuccess: (receipt) => {
      key.next();
      queryClient.invalidateQueries({ queryKey: ["trading"] });
      onRecorded?.(receipt);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  return (
    <section className="trading-section">
      <h2>{t("trading.payment.new").text}</h2>
      <p>{t(invoiceId ? "trading.payment.against_invoice" : "trading.payment.oldest_first").text}</p>
      <div className="trading-filter-bar">
        <label className="trading-form-field">
          {t("trading.payment.method").text}
          <select value={form.method} onChange={(event) => set({ method: event.target.value as PaymentForm["method"] })}>
            {METHODS.map((method) => (
              <option key={method} value={method}>
                {t(`trading.payment.method.${method}`).text}
              </option>
            ))}
          </select>
        </label>
        <label className="trading-form-field">
          {t("trading.payment.amount").text}
          <input type="number" min="0.01" step="0.01" value={form.amount} onChange={(event) => set({ amount: event.target.value })} />
        </label>
        <label className="trading-form-field">
          {t("trading.payment.reference").text}
          <input type="text" maxLength={100} value={form.reference} onChange={(event) => set({ reference: event.target.value })} />
        </label>
        <label className="trading-form-field">
          {t("trading.payment.received_on").text}
          <input type="date" max={today} value={form.receivedOn} onChange={(event) => set({ receivedOn: event.target.value })} />
        </label>
        {form.method === "CHEQUE" && (
          <>
            <label className="trading-form-field">
              {t("trading.payment.bank").text}
              <input type="text" maxLength={100} value={form.bank} onChange={(event) => set({ bank: event.target.value })} />
            </label>
            <label className="trading-form-field">
              {t("trading.payment.cheque_no").text}
              <input type="text" maxLength={20} value={form.chequeNo} onChange={(event) => set({ chequeNo: event.target.value })} />
            </label>
            <label className="trading-form-field">
              {t("trading.payment.cheque_dated").text}
              <input type="date" value={form.chequeDated} onChange={(event) => set({ chequeDated: event.target.value })} />
            </label>
          </>
        )}
        <button type="button" disabled={record.isPending || !paymentReady(form)} onClick={() => record.mutate()}>
          {t("trading.payment.record").text}
        </button>
      </div>
      {record.isError && <p role="alert">{errorText(record.error, t("trading.error.generic").text)}</p>}
    </section>
  );
}
