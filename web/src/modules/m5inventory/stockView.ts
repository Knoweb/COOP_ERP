import { ApiProblem } from "../../shell/api/client";
import type { ChipState } from "../../shell/components/StateChip";
import type {
  IssueTransferRequest,
  Location,
  LotBalance,
  OpeningBalance,
  OpeningBalanceLineRequest,
  RequestTransferRequest,
  TransferRequest
} from "./inventoryApi";

// A batch-less SKU still needs a batch row (M2-05, doc 22 section 3.7), so M2 registers a
// synthetic one numbered `S-<document number>-<line>` (BatchGuards.syntheticBatchNo on the
// backend). It is not a number anyone printed or should read: the stock position showed it as if
// it were a real batch (e.g. "S-OPB-01a0e468-9" for white sugar, the review of 28 September
// 2026). There is no `synthetic` flag on LotBalanceResponse yet (a later contract change), so
// the screen recognises the backend's own fixed shape instead of showing it.
const SYNTHETIC_BATCH_NO = /^S-.+-\d+$/;

/** Whether a batch number is one M2 invented for a batch-less SKU, not a real, printed one. */
export function isSyntheticBatchNo(batchNo: string | undefined): boolean {
  return batchNo !== undefined && SYNTHETIC_BATCH_NO.test(batchNo);
}

/**
 * The lines of a transfer from the quantities typed against the source's lots (keyed by batch):
 * a blank, zero or unreadable quantity sends nothing of that lot.
 */
export function transferLinesOf(lots: LotBalance[], quantities: Record<string, string>): IssueTransferRequest["lines"] {
  return lots
    .map((lot) => ({ batchId: lot.batchId, qty: Number((quantities[lot.batchId] ?? "").trim()) }))
    .filter((line) => Number.isFinite(line.qty) && line.qty > 0);
}

/** The look of an opening balance's state (doc 30 section 2.2): being prepared, signed once, posted. */
export function chipOf(status: OpeningBalance["status"]): ChipState {
  return status === "POSTED" ? "issued" : "draft";
}

/** The problem's title, which the server has already translated; the fallback otherwise. */
export function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiProblem && error.problem.title ? error.problem.title : fallback;
}

/** A counted line as staff type it: numbers and dates stay text until the request is built. */
export type CountedRow = {
  skuId: string;
  label: string;
  batchNo: string;
  expiryDate: string;
  printedMrp: string;
  qty: string;
  unitCost: string;
  condition: "GOOD" | "DAMAGED";
};

/**
 * The request line of a counted row: the batch as counted, which the server registers in M2
 * (M5-12). Empty optional fields are left out, so M2's own guards say what the item needs.
 */
export function lineOf(row: CountedRow): OpeningBalanceLineRequest {
  return {
    skuId: row.skuId,
    batchNo: row.batchNo.trim() || undefined,
    expiryDate: row.expiryDate || undefined,
    printedMrp: row.printedMrp ? Number(row.printedMrp) : undefined,
    qty: Number(row.qty),
    unitCost: Number(row.unitCost),
    condition: row.condition
  };
}

/** A row can be sent once it names an item, a quantity above zero and a cost of zero or more. */
export function rowReady(row: CountedRow): boolean {
  return row.skuId !== "" && Number(row.qty) > 0 && row.unitCost !== "" && Number(row.unitCost) >= 0;
}

/** A transfer request waits (draft), is approved (issued) or refused (void) (M4-10). */
export function requestChip(status: TransferRequest["status"]): ChipState {
  return status === "REQUESTED" ? "draft" : status === "APPROVED" ? "issued" : "void";
}

/** The lines of a transfer request: the items given a quantity above zero, the others left out. */
export function requestLinesOf(quantities: Record<string, string>): RequestTransferRequest["lines"] {
  return Object.entries(quantities)
    .map(([skuId, text]) => ({ skuId, qty: Number(text) }))
    .filter((line) => Number.isFinite(line.qty) && line.qty > 0);
}

/**
 * The location the society approves a request from: the one it chose on the screen, else the one
 * the shop named, else its only warehouse; undefined while it must still choose.
 */
export function sourceFor(
  request: TransferRequest,
  chosen: Record<string, string>,
  locations: Location[]
): string | undefined {
  const picked = chosen[request.requestId];
  if (picked) {
    return picked;
  }
  if (request.fromLocationId) {
    return request.fromLocationId;
  }
  const warehouses = locations.filter((l) => l.locationType === "WAREHOUSE");
  return warehouses.length === 1 ? warehouses[0].locationId : undefined;
}
