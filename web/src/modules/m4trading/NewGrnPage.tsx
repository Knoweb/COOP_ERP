import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useT } from "../../shell/i18n/useT";
import { ApiProblem } from "../../shell/api/client";
import { useIdempotencyKey } from "../../shell/api/idempotency";
import { LocationName, SkuLabel } from "./labels";
import { useTradingApi, type DeliveryNote } from "./tradingApi";
import { countReady, errorText, grnRequest, isShort, type CountRow } from "./tradingView";

type Drop = DeliveryNote["drops"][number];

/** The rows to count: one per item of the drop, expecting what was sent, received as sent until counted. */
function rowsOf(drop: Drop): CountRow[] {
  const rows = new Map<string, CountRow>();
  for (const line of drop.lines) {
    const row = rows.get(line.skuId);
    if (row) {
      row.expected += line.dispatchedQty;
      row.received = String(row.expected);
    } else {
      rows.set(line.skuId, {
        skuId: line.skuId,
        uomCode: line.uomCode,
        expected: line.dispatchedQty,
        received: String(line.dispatchedQty),
        damaged: "",
        batchNo: "",
        expiryDate: "",
        printedMrp: ""
      });
    }
  }
  return [...rows.values()];
}

/**
 * The goods received note of a drop (24A section 6 CaptureGrn; section 8, "Receive", demo
 * scope): the receiver counts each item of the drop at the location it was shipped to; a line
 * where less arrived is kept short (the GRN confirms and a discrepancy is raised). The batch as
 * printed on the goods starts from the seller's batch on the delivery line (M2), and is what M2
 * registers when the GRN is confirmed on its card, where ownership passes (AGENTS.md idea 2).
 */
export function NewGrnPage() {
  const [params] = useSearchParams();
  const deliveryNoteId = params.get("deliveryNoteId") ?? "";
  const dropId = params.get("dropId") ?? "";
  const t = useT();
  const api = useTradingApi();
  const navigate = useNavigate();
  const key = useIdempotencyKey();
  const [rows, setRows] = useState<CountRow[]>([]);

  const note = useQuery({
    queryKey: ["trading", "note", deliveryNoteId],
    queryFn: () => api.deliveryNote(deliveryNoteId),
    enabled: deliveryNoteId !== ""
  });
  const drop = note.data?.drops.find((row) => row.dropId === dropId);
  const batchIds = [...new Set((drop?.lines ?? []).flatMap((line) => (line.batchId ? [[line.skuId, line.batchId]] : [])).map((pair) => pair.join("|")))];
  const batches = useQuery({
    queryKey: ["trading", "batches", batchIds.join(",")],
    queryFn: () =>
      Promise.all(
        batchIds.map(async (pair) => {
          const [skuId, batchId] = pair.split("|");
          return { skuId, batch: await api.batch(batchId).catch(() => null) };
        })
      ),
    enabled: batchIds.length > 0
  });

  // The rows start from the drop once; the seller's batches, which arrive later, fill only the
  // batch fields still empty, never a count the receiver has already typed. Another drop (Back
  // and Forward between two ?dropId= addresses keep this page mounted) starts again from its
  // own lines and with a new key: the rows of one drop are never posted under another.
  const dropKey = drop?.dropId;
  const rowsBuiltFor = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (drop) {
      const sameDrop = rowsBuiltFor.current === drop.dropId;
      if (rowsBuiltFor.current !== undefined && !sameDrop) {
        key.next();
      }
      rowsBuiltFor.current = drop.dropId;
      setRows((current) => (sameDrop && current.length > 0 ? current : rowsOf(drop)));
    }
  }, [dropKey]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!batches.data) {
      return;
    }
    setRows((current) =>
      current.map((row) => {
        const batch = batches.data.find((found) => found.skuId === row.skuId)?.batch;
        if (!batch || row.batchNo !== "") {
          return row;
        }
        return {
          ...row,
          batchNo: batch.batchNo,
          expiryDate: row.expiryDate || (batch.expiryDate ?? ""),
          printedMrp: row.printedMrp || (batch.printedMrp !== undefined && batch.printedMrp !== null ? String(batch.printedMrp) : "")
        };
      })
    );
  }, [batches.data, rows.length]);

  const capture = useMutation({
    mutationFn: () => api.captureGrn(grnRequest(dropId, drop!.shipToLocationId, rows), key.current()),
    onSuccess: (grn) => {
      key.next();
      navigate(`/trading/grns/${grn.grnId}`);
    },
    onError: (error) => {
      if (error instanceof ApiProblem) {
        key.next();
      }
    }
  });

  if (note.isLoading) {
    return <main className="shell-page">{t("trading.loading").text}</main>;
  }
  if (note.isError || !drop) {
    return (
      <main className="shell-page">
        <p role="alert">{errorText(note.error, t("trading.error.not_found").text)}</p>
        <Link className="back-link" to="/trading">
        <svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6" /><path d="M9 12h10" /></svg>
        {t("trading.back").text}
      </Link>
      </main>
    );
  }

  const setRow = (index: number, change: Partial<CountRow>) =>
    setRows((current) => current.map((row, at) => (at === index ? { ...row, ...change } : row)));
  const send = (event: FormEvent) => {
    event.preventDefault();
    capture.mutate();
  };
  const cell = (index: number, field: "received" | "damaged" | "batchNo" | "expiryDate" | "printedMrp", type: string, labelId: string) => (
    <td>
      <input
        type={type}
        min={type === "number" ? "0" : undefined}
        step={type === "number" ? "any" : undefined}
        aria-label={t(labelId).text}
        value={rows[index][field]}
        onChange={(event) => setRow(index, { [field]: event.target.value })}
      />
    </td>
  );

  return (
    <main className="shell-page">
      <Link to={`/trading/delivery-notes/${deliveryNoteId}`}>{t("trading.back_to_note").text}</Link>
      <h1>{t("trading.grn.new.title", undefined, { number: note.data?.docNumber ?? "" }).text}</h1>
      <p>
        {t("trading.field.receive_at").text}: <LocationName locationId={drop.shipToLocationId} />
      </p>
      <form onSubmit={send} className="trading-form-row">
        <table>
          <thead>
            <tr>
              <th>{t("trading.column.item").text}</th>
              <th>{t("trading.column.expected").text}</th>
              <th>{t("trading.column.received").text}</th>
              <th>{t("trading.column.damaged").text}</th>
              <th>{t("trading.column.batch").text}</th>
              <th>{t("trading.column.expiry").text}</th>
              <th>{t("trading.column.mrp").text}</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={row.skuId}>
                <td>
                  <SkuLabel skuId={row.skuId} />
                </td>
                <td>{row.expected}</td>
                {cell(index, "received", "number", "trading.column.received")}
                {cell(index, "damaged", "number", "trading.column.damaged")}
                {cell(index, "batchNo", "text", "trading.column.batch")}
                {cell(index, "expiryDate", "date", "trading.column.expiry")}
                {cell(index, "printedMrp", "number", "trading.column.mrp")}
                <td>{isShort(row) && <strong>{t("trading.grn.short").text}</strong>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div>
          <button type="submit" disabled={capture.isPending || rows.length === 0 || !countReady(rows)}>
            {t("trading.grn.capture").text}
          </button>
        </div>
        {capture.isError && <p role="alert">{errorText(capture.error, t("trading.error.generic").text)}</p>}
      </form>
    </main>
  );
}
