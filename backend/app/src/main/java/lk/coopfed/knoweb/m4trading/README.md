# m4trading — M4 Trading Documents

The living guide of M4 (AGENTS.md): once code exists, this file, the seed files and the tests supersede `docs/design/24A_M4_Implementation_Guide` for day-to-day work. Every deviation from the guide is recorded at the end, with the reason. Read `hello/README.md` first: its six rules apply here unchanged. The reasoning behind the module is `docs/design/24_M4_Trading_Documents`; the numbering decisions are `24B`.

M4 records the inter-entity flow from order to settlement: order, delivery note, goods received note (the pivot, where ownership passes: AGENTS.md idea 2), discrepancy, claim, invoice, credit and debit note, payment receipt, exposure and statement. Money is derived from links and never moved.

**Built for the demo of 27 September 2026** (the architect's priority, `docs/PROGRESS.md`): each ticket to its happy path, its guards, audit, events, RLS and tests; the heavy edge cases are deferred and listed in `docs/PLAN_TO_M2.md`, "Deferred after the demo".

## What is where (after M4-01)

| Path | What it holds |
|---|---|
| `api/` | The published contract: the event records of the document families the demo builds (`order.*`, `delivery_note.*`, `grn.*`, `discrepancy.raised.v1`, `invoice.issued.v1`, `journal.postings_ready.v1`) with their value records, published in M4-01 so that M5's consumers can bind to them; and the three questions M4 asks of modules not built yet, `TradePricing` (M3), `TaxRates` (M2), `InventoryAvailability` (M5). Command records arrive with their tickets. |
| `query/` | The read-only queries of doc 24 section 5.2, with their tickets. |
| `internal/integration/` | The answers to the three questions: `M3TradePricing` (M3's `PricingQueries.resolveTradePrice`, since M4-04), `M5InventoryAvailability` (M5's `InventoryQueries.availability` over the seller's active warehouses, since M4-05); for the demo, from the configuration register, `DemoTaxRates` (`m4.demo.vat_rate_percent`), deleted when M2 publishes the rate in force. |
| `internal/seed/M4SeedLoader` | Loads `seed/m4trading/posting-map.yaml` into `trading.posting_map` on start, as the migrator (the arrangement of `M1SeedLoader` and `M2SeedLoader`), upserted by key. |
| `resources/db/migration/m4trading/V0001__trading.sql` | The tables of M4-01 (below), their row-level security and grants. |
| `resources/seed/m4trading/` | `posting-map.yaml` (doc 24 section 3.9), `audit-event-types.yaml` (the codes of 24A section 6 the demo tickets use). |
| `resources/seed/m1party/permissions.yaml` | The 28 `m4trading` permissions at the end of M1's file (24A section 3.1's 27 and `trd.document.view`, CR-24A-1); `role-templates.yaml` gains "Trading Buyer" and "Trading Seller". |
| `resources/seed/kernel/config-items.yaml` | The `trading.*` items of 24A section 3.1 (`seed/m4/config.yaml`) and the three `m4.demo.*` items, module `m4trading`. |
| `resources/openapi/m4trading.yaml` | The slice; no operation until M4-02. |
| `web/src/modules/m4trading/` | The module registration only: no route, no navigation entry, until M4-11. |
| `src/test/.../m4trading/` | `TradingSchemaIntegrationTest` (tables, forced RLS, policies, grants by column), `TradingRlsIntegrationTest` (the matrix rows: an extension row follows its header, a shop session at its shop, the seller's allocation read by the buyer), `internal/seed/M4SeedLoaderTest`. |

## The schema

Every trading document stands on the kernel base (`kernel.document`, `document_line`, `document_link`, `document_state_history`; K-07): the header carries the parties, the location, the status, the number and the totals; the lines carry SKU, unit, quantity, price and tax. M4 keeps what the base does not, in extension tables keyed by `document_id`:

| Table | Whose | What it holds |
|---|---|---|
| `doc_order`, `doc_order_line` | the buyer's | relationship, parties, requested ETA, version; per line the requested and cancelled quantity |
| `allocation_run`, `order_allocation`, `order_allocation_line` | the seller's | the run (rule, availability snapshot, overrides) and its decision on one order: ACCEPTED with committed ETA, lock time and per-line allocated, fulfilled and tier price, or REJECTED with a reason. The buyer is the counterparty and reads it |
| `doc_delivery`, `doc_delivery_drop`, `doc_delivery_line` | the seller's | vehicle, driver, dispatch; the drops (ship-to shop, bill-to entity, orders served); per line the drop, the order line and the quantity dispatched |
| `doc_grn`, `doc_grn_line` | the receiver's | receiver entity and location, the drop or the supplier, confirmation; per line expected, received and damaged quantities, the batch data keyed, and after confirmation the batch id and unit cost |
| `doc_discrepancy`, `doc_discrepancy_line` | the receiver's | the GRN and delivery it disputes, kind, window, the two-step resolution (M4-06); per line expected, received, damaged, variance |
| `doc_invoice` | the seller's | relationship, parties, the GRNs invoiced, VAT numbers, tax point and due dates, the settled and credited caches |
| `posting_map` | reference | account roles per document type, line kind and side (seeded) |

**Who writes what.** A document's rows are written by the document's owner and by nobody else (AGENTS.md idea 3; `kernel.document_owned`). So the seller's acceptance of the buyer's order is the seller's own record (`order_allocation`), the receiver's GRN never changes the seller's delivery note (a drop's RECEIVED state and the note's CLOSED state are read from the GRNs that name the drops), and the invoice cites the buyer's GRNs by id. The order's state as the screens show it is derived: SUBMITTED from the document, ACCEPTED or REJECTED from the seller's allocation, LOCKED from its lock time, PARTIALLY_FULFILLED or FULFILLED from the allocation lines' fulfilled quantity, CANCELLED from the buyer's own transition (CR-24A-1).

**Row-level security.** The extension tables carry `document_read` on `kernel.document_visible(document_id)`, `document_write` and, where a handler updates a column, `document_update` on `kernel.document_owned(document_id)` (`db/migration/RLS_POLICY_TEMPLATE.md`, "The rows of a document"): a row is seen under whichever class sees its document (the owner in OWN, at its location when the session is location-scoped; the counterparty through PARTY; FEDERATION_VIEW; an external grant) and written by the owner in an OWN scope only. `allocation_run` takes the template without a location line; `order_allocation` and `order_allocation_line` take it with `party_read`. `posting_map` has `reference_read` (every class but NONE) and `seed_reference TO app_seed`. `app_rw` inserts and reads everywhere, updates by column where named in V0001, and deletes nothing.

## Numbering (24B)

ORD from the buyer's ENTITY series; DN and INV from the seller's; GRN from the receiving shop's LOCATION series or the receiving entity's ENTITY series (the kernel picks the finest that exists, `JdbcNumberingService.seriesFor`); DISC from the issuer's ENTITY series. Nothing registers an ENTITY series today (M1's `SeriesHooks` registers LOCATION and TILL_POSITION series only), so M4 registers the ENTITY series of a type before its first issuance, through `NumberingService.registerSeries`, which is idempotent and audited once (M4-02, `TradingSeries`). No series resets; a void keeps its number.

## Seeds

| File | Table | Rule |
|---|---|---|
| `posting-map.yaml` | `trading.posting_map` | upserted by (type, line kind, side, debit, credit); the amount source follows the file |
| `audit-event-types.yaml` | `kernel.audit_event_type` | the audit codes of 24A section 6 used by the demo tickets, all INFO |
| `seed/m1party/permissions.yaml` | `security.permission` | the 28 `m4trading` codes (`M1SeedLoaderTest` counts them) |
| `seed/kernel/config-items.yaml` | `kernel.config_item` | `trading.availability_mode`, `backorder_review_days`, `grn_reversal_hours`, `claim_window_days`, `escalation_grace_days`, `invoice_consolidation`, `exposure_warn_thresholds`, `statement_frequency`, `tier_basis`; `m4.demo.vat_rate_percent` (`m4.demo.trade_price` until M4-04, `m4.demo.availability_qty` until M4-05) |

## What the next tickets build on

- **M4-02 orders**: `doc_order`, `doc_order_line`; the ORD `DocumentTypeHandler`; `TradingSeries`; the order events; `TradePricing` and `InventoryAvailability` for the indicative price and availability of a draft.
- **M4-03 acceptance**: `allocation_run`, `order_allocation`, `order_allocation_line`; `order.accepted/allocated/rejected.v1`.
- **M4-04 delivery notes**: `doc_delivery*`; `delivery_note.*`; the fulfilled quantity on the seller's allocation lines.
- **M4-05 GRN**: `doc_grn*`, `doc_discrepancy*`; `grn.captured/confirmed.v1`, `discrepancy.raised.v1`; M2's `BatchRegistration` inside the confirmation.
- **M4-08 invoices**: `doc_invoice`, `posting_map`; `invoice.issued.v1`, `journal.postings_ready.v1`; `TaxRates`.

## Orders (M4-02, M4-03)

| Command | Permission | Guards, in order | Effect | Audit, event |
|---|---|---|---|---|
| CreateOrder | `ord.order.draft` | buyer entity-wide OWN; seller not the buyer; ACTIVE relationship today (M1); ETA not past; lines; per line a tradable item (M2), its base unit, qty > 0 | kernel draft (lines at the indicative `TradePricing` price), `doc_order`, `doc_order_line` | ORDER_CREATED, `order.created.v1` |
| SubmitOrder | `ord.order.submit` | owner's DRAFT; relationship ACTIVE; ≥ 1 line (ORD validator) | buyer's ENTITY series (`TradingSeries`), issued, ISSUED to SUBMITTED | ORDER_SUBMITTED, `order.submitted.v1` |
| CancelOrder | `ord.order.submit` | owner's DRAFT or SUBMITTED; not rejected; nothing fulfilled; reason | CANCELLED; `cancelled_qty` = request | ORDER_CANCELLED, `order.cancelled.v1` |
| AcceptOrder | `ord.order.accept` | seller entity-wide OWN; order placed with the caller; SUBMITTED, undecided; relationship ACTIVE; ETA not past; overrides on known lines with a reason, ≤ open request, ≤ available; a trade price per line | `allocation_run`, `order_allocation` ACCEPTED (ETA, `lock_at` = ETA day start − lock hours), `order_allocation_line` (allocated = min(open, available) or the override; tier price) | ORDER_ACCEPTED, `order.accepted.v1`, `order.allocated.v1` |
| RejectOrder | `ord.order.accept` | as AcceptOrder's first three; reason | `order_allocation` REJECTED | ORDER_REJECTED, `order.rejected.v1` |

## Delivery notes (M4-04)

| Command | Permission | Guards, in order | Effect | Audit, event |
|---|---|---|---|---|
| CreateDeliveryNote | `del.note.draft` | seller entity-wide OWN; drops with ship-to, bill-to, lines; one buyer; per line an order line the caller accepted, not cancelled, billed to its buyer, qty > 0, within allocated − fulfilled | kernel draft (lines at the tier price, the order line as reference), `doc_delivery`, drops, lines | DN_CREATED, `delivery_note.created.v1` |
| IssueDeliveryNote | `del.note.issue` | owner's DRAFT; allocated − fulfilled re-checked under lock | seller's ENTITY series of DN, issued; `fulfilled_qty` raised | DN_ISSUED, `delivery_note.issued.v1` |
| DispatchDeliveryNote | `del.note.dispatch` | owner's ISSUED; vehicle; driver | vehicle, driver, `dispatched_at`; ISSUED to IN_TRANSIT | DN_DISPATCHED, `delivery_note.dispatched.v1` |

`internal/integration/M3TradePricing` answers `TradePricing` from M3's `PricingQueries.resolveTradePrice` since M4-04 (the register's demo price is gone).

## Goods received notes (M4-05)

| Command | Permission | Guards, in order | Effect | Audit, event |
|---|---|---|---|---|
| CaptureGrn | `shop.grn.confirm` | receiver OWN (entity-wide or at the location); own location; drop of an issued note billed to the caller, shipped there, not yet captured; lines of items on the drop, once, delivered unit, 0 ≤ damaged ≤ received | kernel draft at the location, `doc_grn`, `doc_grn_line` (expected from the drop; uncounted items received as 0) | GRN_CAPTURED, `grn.captured.v1` |
| ConfirmGrn | `shop.grn.confirm` | owner's DRAFT at its location; MRP and expiry where M2 needs them | LOCATION series of a shop or ENTITY series; DRAFT → ISSUED → CONFIRMED; M2 RegisterBatch per line received (internal command, same transaction); batch id and cost on the line; on a variance the DISC document at the GRN's location, RAISED, DISPUTES link | GRN_CONFIRMED, DISCREPANCY_RAISED; `grn.confirmed.v1` (frozen), `discrepancy.raised.v1` |

**The GRN contract is frozen** (24A section 10): `grn.confirmed.v1` and `GrnLineConfirmed` do not change without a change request; M5 and M6 bind to them.

## Invoices (M4-08)

| Command | Permission | Guards, in order | Effect | Audit, event |
|---|---|---|---|---|
| IssueInvoice | `bil.invoice.issue` | seller entity-wide OWN; GRNs; each CONFIRMED, received from the caller, one buyer and relationship, not invoiced; seller VAT number; a VAT rate per line | lines at received qty × the GRN line's trade price, VAT per line; seller's ENTITY series of INV; `doc_invoice` | INVOICE_ISSUED; `invoice.issued.v1`, `journal.postings_ready.v1` (`PostingMapper`, seller side) |

Shared pieces in `internal/document`: `TradingClock` (today in the business zone, a state history row), `TradingSeries`, `TradingGuards`, `TradingDocuments` (draft header and line builders). `internal/queries/OrderStatus` derives the status the screens show. Only a `@CommandHandler` class writes (ArchitectureTests), so the handlers hold their own SQL.

## Deviations from the implementation guide

Every difference between the schema as migrated and 24A section 3, and between the handlers and 24A sections 5 and 6, with the reason. Items 1 to 5 are recorded in `docs/change-requests/CR-24A-1.md`, decided 27 September 2026 on the architect's delegation.

1. **The extension tables have no `owner_entity_id` and no `received_at`, and are not partitioned.** 24A section 3 writes both on every table and partitions them like the base. The kernel base on `main` is not partitioned (K-07), a partition key in every primary key would travel into every reference, and the demo is nowhere near the volume; the rows of a document follow their header's policies instead (the template's fourth case), so there is no owner column to keep in step. Partitioning is deferred after the demo.
2. **The seller's acceptance is the seller's record.** 24A's `doc_order` and `doc_order_line` carry `allocated_qty`, `fulfilled_qty`, `tier_price`, `committed_eta`, `lock_at`, `lock_override_by`, `allocation_run_id`, written by AcceptOrder and IssueDeliveryNote, which the seller runs on the buyer's document. Under the kernel's document ownership (`kernel.document_owned`, only the owner writes a document's rows; AGENTS.md idea 3) the seller cannot, so those columns are `trading.order_allocation` and `order_allocation_line`, owned by the seller with the buyer as counterparty, and the order's ACCEPTED, REJECTED, LOCKED and FULFILLED states are derived from them. `doc_order` keeps the buyer's request only.
3. **A drop's RECEIVED state and the delivery note's CLOSED state are derived**, not written by `drops.markReceived` (24A section 6.1): the GRN is the receiver's, the note the seller's. The delivery queries read the confirmed GRNs that name the drops.
4. **`doc_grn_line` carries `batch_id` and `unit_cost`, written after issuance.** 24A puts the batch on the kernel line. A kernel line is frozen when the document takes its number, and RegisterBatch needs the number for a synthetic batch (`S-<GRN number>-<line>`, doc 22 section 3.7), so the batch is registered after the issue and written on the extension row, in the same transaction. `grn.confirmed.v1` carries the batch id per line from there.
5. **`trd.document.view`** (ENTITY) is the permission of every read of the slice: the kernel checks a GET's `x-permission` (#144) and 24A section 3.1 names no read code. Beyond 24A's 27 codes.
6. **The kernel's issuance lands every document on ISSUED**; a type whose first issued state has another name (ORD SUBMITTED, GRN CONFIRMED) moves there through `DocumentBaseRepository.addStateTransition` in the same transaction, so the history shows DRAFT to ISSUED to SUBMITTED. DN and INV stay ISSUED.
7. **ORDER_CREATED, DN_CREATED and GRN_CAPTURED audit codes, and `order.created.v1`, `delivery_note.created.v1`**: 24A section 6 names no audit code or event for the drafts; every command audits and publishes (AGENTS.md).
8. **The three questions to M3, M2 and M5 are interfaces of `api`** (`TradePricing`, `TaxRates`, `InventoryAvailability`), answered for the demo from the register; 24A section 4 names `m3pricing::query` and `m5inventory::query`, which do not exist yet, and M2 publishes no tax query.
9. **`doc_grn` has `relationship_id`** (the relationship the delivery was made under, kept for the invoice and the cost basis) and a CHECK that a GRN names a drop or a supplier, never both; `doc_order_line` and `doc_delivery_line` carry `document_id` for the header policy (24A's `doc_delivery_line` keys on the drop only).
10. **Two role templates, "Trading Buyer" and "Trading Seller"**: 24A section 3.1 seeds permissions only; the demo's staff need roles that hold them.
11. **Order lines in the base unit only (M4-02)**: M2 publishes no unit conversion query yet, so CreateOrder refuses another unit (`m4.order.uom_invalid`) until it does.
12. **CancelOrder cancels a whole order before any dispatch (M4-02)**: 24A cancels the undispatched remainder; the demo refuses once anything is fulfilled (`m4.order.dispatched`).
13. **The seller does not check that a drop's ship-to shop belongs to its bill-to entity (M4-04)**: it cannot read the buyer's locations (m1party `own_read`); CaptureGrn, at a location of the receiver's own, is where a wrong ship-to shows.
14. **CaptureGrn and ConfirmGrn carry `shop.grn.confirm` for every location (M4-05)**: 24A resolves `shop.grn.confirm | whs.grn.confirm` by location type; a handler has one permission, so the warehouse code waits until the demo is past.
16. **Availability is the seller's own (M4-05)**: M5's lots and M1's locations are read under the caller's row-level security, so a buyer cannot see a seller's availability; the endpoint answers 0 for another entity until a masked read exists.
15. **One GRN per drop, of the drop's items only (M4-05)**; the uncounted item of a drop is received as zero, so a short delivery is always a line of the discrepancy.
