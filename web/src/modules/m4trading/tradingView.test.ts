import { describe, expect, it } from "vitest";
import messages from "./trading.messages.json" with { type: "json" };
import type { Order, OrderLine } from "./tradingApi";
import {
  acceptedLines,
  claimChip,
  amendReady,
  amendRequest,
  inForce,
  canDeliver,
  countReady,
  exposureAfter,
  paymentReady,
  paymentRequest,
  paymentStateChip,
  percentOfLimit,
  receiptChip,
  type PaymentForm,
  deliveryRequest,
  firstOpenEta,
  grnRequest,
  isShort,
  isSyntheticBatchNo,
  lineAmount,
  openQty,
  orderChip,
  orderRequest,
  requisitionReady,
  type CountRow
} from "./tradingView";

const SELLER = "0190f000-0000-7000-8000-000000000001";
const BUYER = "0190f0de-0000-7000-8000-0000000000e1";
const W01 = "0190f0de-0000-7000-8000-000000000111";

const line = (change: Partial<OrderLine> = {}): OrderLine => ({
  lineId: "0190f4aa-0000-7000-8000-000000000001",
  lineNo: 1,
  skuId: "0190f4bb-0000-7000-8000-000000000001",
  uomCode: "EA",
  requestedQty: 120,
  cancelledQty: 0,
  ...change
});

const order = (change: Partial<Order> = {}): Order => ({
  orderId: "0190f4cc-0000-7000-8000-000000000001",
  status: "ACCEPTED",
  relationshipId: "0190f4dd-0000-7000-8000-000000000001",
  buyerEntityId: BUYER,
  sellerEntityId: SELLER,
  deliverToLocationId: W01,
  lines: [line({ allocatedQty: 120, fulfilledQty: 0 })],
  ...change
});

const count = (change: Partial<CountRow> = {}): CountRow => ({
  skuId: "0190f4bb-0000-7000-8000-000000000001",
  uomCode: "EA",
  expected: 120,
  received: "120",
  damaged: "",
  batchNo: " DEMO-2026-1 ",
  expiryDate: "2027-03-31",
  printedMrp: "",
  ...change
});

describe("the requisition book", () => {
  it("is ready once every line has a quantity above zero", () => {
    expect(requisitionReady([])).toBe(false);
    expect(requisitionReady([{ skuId: "a", label: "A", uomCode: "EA", qty: "120" }])).toBe(true);
    expect(requisitionReady([{ skuId: "a", label: "A", uomCode: "EA", qty: "0" }])).toBe(false);
  });

  it("sends the seller, the delivery location and the lines in the base unit", () => {
    expect(orderRequest(SELLER, W01, [{ skuId: "a", label: "A", uomCode: "EA", qty: "40" }])).toEqual({
      sellerEntityId: SELLER,
      deliverToLocationId: W01,
      lines: [{ skuId: "a", uomCode: "EA", qty: 40 }]
    });
    expect(orderRequest(SELLER, "", []).deliverToLocationId).toBeUndefined();
  });

  it("prices a line at the tier price, to the cent, and only when both are known", () => {
    expect(lineAmount("120", 1234.5)).toBe(148140);
    expect(lineAmount("3", 0.3333)).toBe(1);
    expect(lineAmount("", 10)).toBeNull();
    expect(lineAmount("5", undefined)).toBeNull();
  });
});

describe("the order desk and the delivery note", () => {
  it("shows a draft as a draft, a refusal as void and the rest as in force", () => {
    expect(orderChip("DRAFT")).toBe("draft");
    expect(orderChip("REJECTED")).toBe("void");
    expect(orderChip("CANCELLED")).toBe("void");
    expect(orderChip("SUBMITTED")).toBe("issued");
    expect(orderChip("PARTIALLY_FULFILLED")).toBe("issued");
  });

  it("proposes the first delivery date that does not lock the order at once", () => {
    expect(firstOpenEta("2026-09-27", 0)).toBe("2026-09-28");
    expect(firstOpenEta("2026-09-27", 24)).toBe("2026-09-29");
    expect(firstOpenEta("2026-09-30", 48)).toBe("2026-10-03");
  });

  it("opens what is allocated and not yet on an issued note", () => {
    expect(openQty(line())).toBe(0);
    expect(openQty(line({ allocatedQty: 120, fulfilledQty: 100 }))).toBe(20);
    expect(canDeliver(order())).toBe(true);
    expect(canDeliver(order({ status: "SUBMITTED" }))).toBe(false);
    expect(canDeliver(order({ lines: [line({ allocatedQty: 120, fulfilledQty: 120 })] }))).toBe(false);
  });

  it("sends one drop to the buyer's delivery location, billed to the buyer, without empty lines", () => {
    const request = deliveryRequest(order(), "FW01", " NB-1234 ", "", [
      { orderLineId: "l1", skuId: "a", qty: "120", batchId: "b1" },
      { orderLineId: "l2", skuId: "b", qty: "0", batchId: "" }
    ]);
    expect(request).toEqual({
      fromLocationId: "FW01",
      vehicleRef: "NB-1234",
      driverName: undefined,
      drops: [{ shipToLocationId: W01, billToEntityId: BUYER, lines: [{ orderLineId: "l1", qty: 120, batchId: "b1" }] }]
    });
  });
});

describe("the goods received note", () => {
  it("allows a short line and marks it", () => {
    expect(isShort(count())).toBe(false);
    expect(isShort(count({ received: "100" }))).toBe(true);
    expect(countReady([count({ received: "100" })])).toBe(true);
  });

  it("refuses more damaged than received, and a line not counted", () => {
    expect(countReady([count({ received: "10", damaged: "11" })])).toBe(false);
    expect(countReady([count({ received: "" })])).toBe(false);
  });

  it("sends the count with the batch as printed", () => {
    expect(grnRequest("drop", W01, [count({ received: "100", damaged: "2" })])).toEqual({
      dropId: "drop",
      locationId: W01,
      lines: [
        {
          skuId: "0190f4bb-0000-7000-8000-000000000001",
          uomCode: "EA",
          receivedQty: 100,
          damagedQty: 2,
          batchNo: "DEMO-2026-1",
          expiryDate: "2027-03-31",
          printedMrp: undefined
        }
      ]
    });
  });
});

describe("recognising a synthetic batch number", () => {
  it("recognises M2's own shape, S-<document>-<line>, whatever the document number looks like", () => {
    expect(isSyntheticBatchNo("S-OPB-01a0e468-9")).toBe(true);
    expect(isSyntheticBatchNo("S-GRN1-1")).toBe(true);
    expect(isSyntheticBatchNo("S-A-B-C-12")).toBe(true);
  });

  it("leaves a real, printed batch number alone, even one a supplier chose to start with S-", () => {
    expect(isSyntheticBatchNo("DEMO-2026-1")).toBe(false);
    expect(isSyntheticBatchNo("SUGAR-01")).toBe(false);
    expect(isSyntheticBatchNo(undefined)).toBe(false);
  });
});

describe("the texts", () => {
  it("has a text for every state and reason in every language", () => {
    const ids = [
      ...["DRAFT", "SUBMITTED", "ACCEPTED", "REJECTED", "LOCKED", "PARTIALLY_FULFILLED", "FULFILLED", "CANCELLED"].map(
        (s) => `trading.order.status.${s}`
      ),
      ...["DRAFT", "ISSUED", "IN_TRANSIT", "CLOSED"].map((s) => `trading.note.status.${s}`),
      ...["DRAFT", "CONFIRMED"].map((s) => `trading.grn.status.${s}`),
      ...["NOT_NEEDED", "WRONG_ITEMS", "NO_STOCK", "NOT_SUPPLIED", "OTHER"].map((r) => `trading.reason.${r}`),
      "trading.condition.GOOD",
      "trading.condition.DAMAGED"
    ];
    for (const locale of ["en", "si", "ta"] as const) {
      const catalogue = messages[locale] as Record<string, string>;
      for (const id of ids) {
        expect(catalogue[id], `${id} in ${locale}`).toBeTruthy();
      }
    }
  });
});

describe("payments and exposure (M4-07, M4-09)", () => {
  const form = (change: Partial<PaymentForm> = {}): PaymentForm => ({
    method: "TRANSFER",
    amount: "1736",
    reference: " TT-1001 ",
    receivedOn: "2026-09-28",
    bank: "",
    chequeNo: "",
    chequeDated: "2026-09-28",
    ...change
  });

  it("is ready with an amount above zero, and a cheque needs its bank and number", () => {
    expect(paymentReady(form())).toBe(true);
    expect(paymentReady(form({ amount: "0" }))).toBe(false);
    expect(paymentReady(form({ amount: "" }))).toBe(false);
    expect(paymentReady(form({ method: "CHEQUE" }))).toBe(false);
    expect(paymentReady(form({ method: "CHEQUE", bank: "Bank of Ceylon", chequeNo: "400123" }))).toBe(true);
  });

  it("settles the chosen invoice, or leaves the settlements to the server's oldest first", () => {
    expect(paymentRequest(BUYER, form(), "inv-1")).toEqual({
      buyerEntityId: BUYER,
      method: "TRANSFER",
      amount: 1736,
      reference: "TT-1001",
      receivedOn: "2026-09-28",
      cheque: undefined,
      settlements: [{ invoiceId: "inv-1", amount: 1736 }]
    });
    const cheque = paymentRequest(BUYER, form({ method: "CHEQUE", bank: " BOC ", chequeNo: "400123", reference: "" }));
    expect(cheque.settlements).toBeUndefined();
    expect(cheque.reference).toBeUndefined();
    expect(cheque.cheque).toEqual({ bank: "BOC", chequeNo: "400123", dated: "2026-09-28" });
  });

  it("measures the exposure against the limit, and what an order would take it to", () => {
    expect(percentOfLimit(24517.88, 27000)).toBe(91);
    expect(percentOfLimit(100, null)).toBeNull();
    expect(percentOfLimit(100, 0)).toBeNull();
    expect(exposureAfter({ amount: 24517.88, creditLimit: 27000 }, 5000)).toEqual({ amount: 29517.88, percent: 109, overLimit: true });
    expect(exposureAfter({ amount: 100, creditLimit: undefined }, 50)).toEqual({ amount: 150, percent: null, overLimit: false });
  });

  it("shows a settled invoice and a receipt in force as issued, the rest as open or void", () => {
    expect(paymentStateChip("SETTLED")).toBe("issued");
    expect(paymentStateChip("PART_PAID")).toBe("draft");
    expect(paymentStateChip(undefined)).toBe("draft");
    expect(receiptChip("RECORDED")).toBe("issued");
    expect(receiptChip("REVERSED")).toBe("void");
    expect(receiptChip("REVERSAL")).toBe("void");
  });
});

describe("amending an order and the relationship in force", () => {
  it("keeps one relationship row per pair in force, not every ACTIVE row", () => {
    const closed = { status: "ACTIVE", effectiveFrom: "2026-01-01", effectiveTo: "2026-09-28" };
    const next = { status: "ACTIVE", effectiveFrom: "2026-09-29", effectiveTo: null };
    expect(inForce(closed, "2026-09-29")).toBe(false);
    expect(inForce(next, "2026-09-29")).toBe(true);
    expect(inForce({ ...next, status: "SUSPENDED" }, "2026-09-29")).toBe(false);
  });

  it("sends the whole set of lines, a line at zero dropped, and refuses an order with none", () => {
    const rows = [
      { skuId: "a", uomCode: "EA", qty: "0" },
      { skuId: "b", uomCode: "KG", qty: "2.5" }
    ];
    expect(amendReady(rows)).toBe(true);
    expect(amendReady([{ skuId: "a", uomCode: "EA", qty: "0" }])).toBe(false);
    expect(amendReady([{ skuId: "a", uomCode: "EA", qty: "" }])).toBe(false);
    expect(amendRequest(rows, "")).toEqual({ requestedEta: undefined, notes: undefined, lines: [{ skuId: "b", uomCode: "KG", qty: 2.5 }] });
  });
});

describe("the claim decision", () => {
  const lines = [
    { claimLineId: "a", claimedQty: 3 },
    { claimLineId: "b", claimedQty: 1 }
  ];

  it("accepts a line in full unless the seller typed less, and refuses more than claimed", () => {
    expect(acceptedLines(lines, {})).toEqual([
      { claimLineId: "a", qty: 3 },
      { claimLineId: "b", qty: 1 }
    ]);
    expect(acceptedLines(lines, { a: "2", b: "0" })).toEqual([
      { claimLineId: "a", qty: 2 },
      { claimLineId: "b", qty: 0 }
    ]);
    expect(acceptedLines(lines, { a: "4" })).toBeNull();
    expect(acceptedLines(lines, { b: "-1" })).toBeNull();
    expect(acceptedLines(lines, { a: "two" })).toBeNull();
  });

  it("shows an open claim as disputed, an approved one as issued and a rejected one as void", () => {
    expect(claimChip("RAISED")).toBe("disputed");
    expect(claimChip("APPROVED")).toBe("issued");
    expect(claimChip("REJECTED")).toBe("void");
  });
});
