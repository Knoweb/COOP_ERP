// What the trading screens compute without the server: the look of a state, the requests built
// from what staff typed, and the quantities still open. Pure functions, tested in
// tradingView.test.ts.

import { ApiProblem } from "../../shell/api/client";
import type { ChipState } from "../../shell/components/StateChip";
import { BUSINESS_TIME_ZONE } from "../../shell/i18n/formats";
import type {
  AmendOrderRequest,
  CaptureGrnRequest,
  CreateDeliveryNoteRequest,
  CreateOrderRequest,
  DeliveryNote,
  Exposure,
  Order,
  OrderLine,
  OrderStatus,
  PaymentReceipt,
  RecordPaymentReceiptRequest
} from "./tradingApi";

// A batch-less SKU still needs a batch row (M2-05, doc 22 section 3.7), so M2 registers a
// synthetic one numbered `S-<document number>-<line>` (BatchGuards.syntheticBatchNo on the
// backend). It is not a number anyone printed or should read: the GRN and delivery note screens
// showed it as if it were a real batch (e.g. "S-OPB-01a0e468-9" for white sugar, the review of
// 28 September 2026). There is no `synthetic` flag on these read slices yet (a later contract
// change), so the screen recognises the backend's own fixed shape instead of showing it.
const SYNTHETIC_BATCH_NO = /^S-.+-\d+$/;

/** Whether a batch number is one M2 invented for a batch-less SKU, not a real, printed one. */
export function isSyntheticBatchNo(batchNo: string | undefined): boolean {
  return batchNo !== undefined && SYNTHETIC_BATCH_NO.test(batchNo);
}

/** The problem's title, which the server has already translated; the fallback otherwise. */
export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}

/** Today's business date (Asia/Colombo), as the server's guards read it: yyyy-mm-dd. */
export function businessToday(now: Date = new Date()): string {
  // Built from the parts, not from a locale's date format ("en-CA" writes yyyy-mm-dd): where the
  // date formatter is the FormatJS polyfill (shell/i18n/localeData.ts) only en, si and ta exist.
  const parts = new Intl.DateTimeFormat("en", {
    timeZone: BUSINESS_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).formatToParts(now);
  const part = (type: string) => parts.find((each) => each.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

/**
 * The first delivery date an acceptance can take without the order locking at once: the order
 * locks `lockHours` before the start of its delivery day (AcceptOrder, M4-03), so the date is
 * the first whole day after that many hours from today. The hours are the relationship's
 * (M1 `orderLockHoursBeforeEta`), never a number of the screen's.
 */
export function firstOpenEta(today: string, lockHours: number): string {
  const date = new Date(`${today}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + Math.ceil(Math.max(0, lockHours) / 24) + 1);
  return date.toISOString().slice(0, 10);
}

/** The look of an order's state (doc 30 section 2.1): being written, in force, or ended without effect. */
export function orderChip(status: OrderStatus): ChipState {
  if (status === "DRAFT") {
    return "draft";
  }
  if (status === "REJECTED" || status === "CANCELLED") {
    return "void";
  }
  return "issued";
}

export function deliveryChip(status: DeliveryNote["status"]): ChipState {
  return status === "DRAFT" ? "draft" : "issued";
}

export function grnChip(status: "DRAFT" | "CONFIRMED"): ChipState {
  return status === "DRAFT" ? "draft" : "issued";
}

/** An open discrepancy is a disagreement still to settle; a settled one is closed and in force. */
export function discrepancyChip(status: "RAISED" | "SETTLED"): ChipState {
  return status === "RAISED" ? "disputed" : "issued";
}

/** A claim waits for the seller (disputed), is approved (issued) or rejected (void). */
export function claimChip(status: "RAISED" | "APPROVED" | "REJECTED"): ChipState {
  return status === "RAISED" ? "disputed" : status === "APPROVED" ? "issued" : "void";
}

/**
 * The accepted quantity of each claimed line as the approval sends it: the seller's figure where it
 * typed one (0 accepted), the claimed quantity where it left the field alone; null when a figure is
 * not a number from 0 to the claimed quantity.
 */
export function acceptedLines(
  lines: { claimLineId: string; claimedQty: number }[],
  typed: Record<string, string>
): { claimLineId: string; qty: number }[] | null {
  const accepted: { claimLineId: string; qty: number }[] = [];
  for (const line of lines) {
    const text = typed[line.claimLineId];
    const qty = text === undefined || text.trim() === "" ? line.claimedQty : Number(text);
    if (!Number.isFinite(qty) || qty < 0 || qty > line.claimedQty) {
      return null;
    }
    accepted.push({ claimLineId: line.claimLineId, qty });
  }
  return accepted;
}

/** An invoice is in force whatever its payments; one still owing is shown as a document still to close. */
export function paymentStateChip(state: "OPEN" | "PART_PAID" | "SETTLED" | undefined): ChipState {
  return state === "SETTLED" ? "issued" : "draft";
}

/** A receipt in force is issued; a bounced one and its reversal are kept for the record, void. */
export function receiptChip(status: PaymentReceipt["status"]): ChipState {
  return status === "RECORDED" ? "issued" : "void";
}

/** The exposure as a share of the credit limit, in whole percent; null when the relationship sets no limit. */
export function percentOfLimit(amount: number, creditLimit: number | undefined | null): number | null {
  if (creditLimit === undefined || creditLimit === null || creditLimit <= 0) {
    return null;
  }
  return Math.round((amount / creditLimit) * 100);
}

/**
 * What accepting an order would leave the exposure at: the order's value at its tier prices (what
 * the acceptance adds, as the server's formula counts it) on top of today's exposure, and whether
 * that passes the credit limit. It warns only: the server accepts it all the same (ADR-12).
 */
export function exposureAfter(
  exposure: Pick<Exposure, "amount" | "creditLimit">,
  orderValue: number
): { amount: number; percent: number | null; overLimit: boolean } {
  const amount = Math.round((exposure.amount + orderValue) * 100) / 100;
  const limit = exposure.creditLimit;
  return {
    amount,
    percent: percentOfLimit(amount, limit),
    overLimit: limit !== undefined && limit !== null && limit > 0 && amount > limit
  };
}

/** A payment as the seller's accounts type it; the amounts stay text until it is sent. */
export type PaymentForm = {
  method: "CASH" | "CHEQUE" | "TRANSFER" | "DEPOSIT";
  amount: string;
  reference: string;
  receivedOn: string;
  bank: string;
  chequeNo: string;
  chequeDated: string;
};

/** Ready to send: an amount above zero, and a cheque's bank, number and date when paid by cheque. */
export function paymentReady(form: PaymentForm): boolean {
  const amount = Number(form.amount);
  if (!Number.isFinite(amount) || amount <= 0 || form.receivedOn === "") {
    return false;
  }
  return form.method !== "CHEQUE" || (form.bank.trim() !== "" && form.chequeNo.trim() !== "" && form.chequeDated !== "");
}

/**
 * The request: against one invoice when `invoiceId` is given (the amount settles it, up to what is
 * due), otherwise with no settlements, so the server settles the buyer's open invoices oldest first.
 */
export function paymentRequest(buyerEntityId: string, form: PaymentForm, invoiceId?: string): RecordPaymentReceiptRequest {
  const amount = Number(form.amount);
  return {
    buyerEntityId,
    method: form.method,
    amount,
    reference: form.reference.trim() === "" ? undefined : form.reference.trim(),
    receivedOn: form.receivedOn,
    cheque:
      form.method === "CHEQUE"
        ? { bank: form.bank.trim(), chequeNo: form.chequeNo.trim(), dated: form.chequeDated }
        : undefined,
    settlements: invoiceId ? [{ invoiceId, amount }] : undefined
  };
}

/** A line of the requisition book as the buyer types it; the quantity stays text until it is sent. */
export type RequisitionRow = {
  skuId: string;
  label: string;
  uomCode: string;
  qty: string;
};

export function requisitionReady(rows: RequisitionRow[]): boolean {
  return rows.length > 0 && rows.every((row) => Number(row.qty) > 0);
}

export function orderRequest(sellerEntityId: string, deliverToLocationId: string, rows: RequisitionRow[]): CreateOrderRequest {
  return {
    sellerEntityId,
    deliverToLocationId: deliverToLocationId || undefined,
    lines: rows.map((row) => ({ skuId: row.skuId, uomCode: row.uomCode, qty: Number(row.qty) }))
  };
}

/** A relationship row ACTIVE and in force on `today` (yyyy-mm-dd): M1 keeps one row per term of a pair. */
export function inForce(row: { status: string; effectiveFrom: string; effectiveTo?: string | null }, today: string): boolean {
  return row.status === "ACTIVE" && row.effectiveFrom <= today && (!row.effectiveTo || row.effectiveTo >= today);
}

/** A line of an order being amended: its item and unit stay, the quantity is typed (0 drops the line). */
export type AmendRow = { skuId: string; uomCode: string; qty: string };

/** The amended order has at least one line, and every quantity is a number, none negative. */
export function amendReady(rows: AmendRow[]): boolean {
  const numbers = rows.map((row) => (row.qty.trim() === "" ? Number.NaN : Number(row.qty)));
  return numbers.every((n) => Number.isFinite(n) && n >= 0) && numbers.some((n) => n > 0);
}

/** AmendOrder's body: the whole set of lines, those at zero left out, and the requested delivery date. */
export function amendRequest(rows: AmendRow[], requestedEta: string, notes?: string): AmendOrderRequest {
  return {
    requestedEta: requestedEta || undefined,
    notes: notes || undefined,
    lines: rows
      .filter((row) => Number(row.qty) > 0)
      .map((row) => ({ skuId: row.skuId, uomCode: row.uomCode, qty: Number(row.qty) }))
  };
}

/** Quantity times unit price, rounded to cents; null when either is unknown. */
export function lineAmount(qty: number | string | undefined, price: number | undefined | null): number | null {
  const n = Number(qty);
  if (price === undefined || price === null || !Number.isFinite(n) || n <= 0) {
    return null;
  }
  return Math.round(n * price * 100) / 100;
}

/** What is allocated to a line and not yet on an issued delivery note. */
export function openQty(line: OrderLine): number {
  return Math.max(0, (line.allocatedQty ?? 0) - (line.fulfilledQty ?? 0));
}

/** The seller may send goods against an order it accepted that still has something open. */
export function canDeliver(order: Order): boolean {
  return (
    (order.status === "ACCEPTED" || order.status === "LOCKED" || order.status === "PARTIALLY_FULFILLED") &&
    order.lines.some((line) => openQty(line) > 0)
  );
}

/** A delivery line as the seller prepares it: the quantity to send and the batch it comes from. */
export type DispatchRow = {
  orderLineId: string;
  skuId: string;
  qty: string;
  batchId: string;
};

/**
 * One drop to the buyer's delivery location, billed to the buyer (demo scope: one order, one
 * drop; 24A section 6 CreateDeliveryNote). Lines with nothing to send are left out.
 */
export function deliveryRequest(
  order: Order,
  fromLocationId: string,
  vehicleRef: string,
  driverName: string,
  rows: DispatchRow[]
): CreateDeliveryNoteRequest {
  return {
    fromLocationId: fromLocationId || undefined,
    vehicleRef: vehicleRef.trim() || undefined,
    driverName: driverName.trim() || undefined,
    drops: [
      {
        shipToLocationId: order.deliverToLocationId ?? "",
        billToEntityId: order.buyerEntityId,
        lines: rows
          .filter((row) => Number(row.qty) > 0)
          .map((row) => ({ orderLineId: row.orderLineId, qty: Number(row.qty), batchId: row.batchId || undefined }))
      }
    ]
  };
}

/** A counted line of a goods received note: what arrived, what of it is damaged, the batch as printed. */
export type CountRow = {
  skuId: string;
  uomCode: string;
  expected: number;
  received: string;
  damaged: string;
  batchNo: string;
  expiryDate: string;
  printedMrp: string;
};

/** A line where less arrived than was sent: the GRN still confirms, and a discrepancy is raised. */
export function isShort(row: CountRow): boolean {
  return Number(row.received || 0) < row.expected;
}

export function countReady(rows: CountRow[]): boolean {
  return rows.every((row) => {
    const received = Number(row.received || 0);
    const damaged = Number(row.damaged || 0);
    return row.received !== "" && received >= 0 && damaged >= 0 && damaged <= received;
  });
}

export function grnRequest(dropId: string, locationId: string, rows: CountRow[]): CaptureGrnRequest {
  return {
    dropId,
    locationId,
    lines: rows.map((row) => ({
      skuId: row.skuId,
      uomCode: row.uomCode,
      receivedQty: Number(row.received || 0),
      damagedQty: row.damaged ? Number(row.damaged) : undefined,
      batchNo: row.batchNo.trim() || undefined,
      expiryDate: row.expiryDate || undefined,
      printedMrp: row.printedMrp ? Number(row.printedMrp) : undefined
    }))
  };
}
