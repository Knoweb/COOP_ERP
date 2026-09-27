# m5inventory — M5 Inventory, Costing, Repack & Loss

The living guide of the module (AGENTS.md): where stock is and what it cost, by location, batch and condition. Once code exists, this file and the tests supersede 25A for day-to-day work; every deviation from the guide is listed at the end with its reason. Read `hello/README.md` first: its six rules apply here unchanged.

Built so far, for the demo (Phase 1: the Federation selling to a distributor): M5-01 (schema), M5-02 (the ledger), M5-04 (the queries, and M5's answer to M2's lot questions), M5-03 (the GRN and delivery consumers) and M5-10 (opening balances). The demo-minimal scope and what was deferred are in `docs/PROGRESS.md` and under "Deferred after the demo" in `docs/PLAN_TO_M2.md`.

## The one rule

**`LedgerService.post` is the only place stock changes** (25A "Read this first"). Receipts, sales, counts, write-offs, repacks, transfers and opening balances are callers: each assembles `Movement`s, cites its document and calls `StockLedger.post` inside its own command. Nothing else writes `stock_lot.qty_on_hand`, `stock_movement` or `entity_sku_cost`; the database grants make most of that impossible anyway (below).

## What is where

| Path | What it holds |
|---|---|
| `api/` | `StockLedger` and its command `PostMovements` (an internal command, CR-19A-6: it runs only inside another command handler); the value types `Movement`, `MovementType`, `LotCondition`, `PostedMovement`; the events `StockMoved` (`stock.moved.v1`) and `LotNegative` (`lot.negative.v1`). |
| `internal/ledger/` | `LedgerService` (the handler), `LedgerGuards` (the guards that need no database), `CostService` (the weighted average, pure arithmetic), `LedgerStore` (the ledger's reads and row locks), `MovementPartitionMaintainer` (the monthly partitions, called by the kernel's partition job). |
| `resources/db/migration/m5inventory/V0001__inventory.sql` | `stock_lot`, `stock_movement` (partitioned monthly by `received_at`), `movement_sequence`, `entity_sku_cost`; row-level security from the template; the partition functions. |
| `resources/seed/m5inventory/audit-event-types.yaml` | The audit codes: `STOCK_POSTED`, `STOCK_LOT_NEGATIVE` (REVIEW), `STOCK_LOT_NEGATIVE_CLEARED`. |
| `resources/seed/m1party/permissions.yaml` | M5's 17 permission codes of 25A section 3.1 and `inv.stock.view`, appended to M1's catalogue. |

## The ledger (25A section 6.1)

`post(PostMovements(documentId, occurredAt, occurredLocal, movements), scope)`:

1. **Guards**: an OWN scope with an entity (`m5.scope.own_required`); a document (`m5.ledger.document_required`); at least one movement (`m5.ledger.movements_required`); each movement complete (`m5.ledger.movement_incomplete`), its quantity non-zero with at most three decimals (`m5.ledger.qty_invalid`), its sign the one its type allows (`m5.ledger.sign_invalid`: RECEIPT, OPENING_BALANCE, REPACK_PRODUCE, TRANSFER_IN and SALE_REVERSAL add; COUNT_ADJUST either way; the rest take away), its cost given for a type that carries one (`m5.ledger.cost_required`, at most four decimals `m5.ledger.cost_invalid`); each batch known to M2 (`m5.batch.not_found`); each location one of the scope entity's that the scope reads through M1 (`m5.location.not_in_scope`: a shop session posts at its own shop only). **A negative lot is not refused**: an offline oversell takes the lot below zero and the record shows what the till believed (doc 25 section 3.2).
2. **Locks, in one order**, so two postings cannot deadlock: every lot the posting touches, created if missing, in the order of its identity (location, batch, condition); then the movement numbers, a block per location in location order; then the entity's cost rows in SKU order.
3. **Movements, in the order given**: each gets its number (dense per location and source; the source is `central` or the device id), its cost (`costFor`: the caller's for an intake or a transfer in, the entity average otherwise), moves its lot, and moves the entity average.
4. **Audit** `STOCK_POSTED` for the document; `STOCK_LOT_NEGATIVE` (REVIEW) when a lot goes below zero, `STOCK_LOT_NEGATIVE_CLEARED` when it comes back. **Events** `stock.moved.v1` per movement, `lot.negative.v1` per lot that went below zero.

A lot created by a movement that carries no cost of its own (a sale against a lot the location never had, doc 25 flow 6.2) starts at the entity average.

### The weighted average (`CostService`; doc 25 section 3.3)

Per (entity, SKU): an intake at cost (RECEIPT, OPENING_BALANCE, REPACK_PRODUCE) re-averages, `(qty × avg + qtyIn × costIn) / (qty + qtyIn)`, cost scale 4, half up; every other movement changes the quantity and keeps the average (the last known one at zero). A TRANSFER_IN carries the source lot's cost but leaves the average alone: the stock never left the entity. When the entity holds nothing or owes stock (after an oversell), an intake's cost becomes the average: there is nothing to average with, and averaging with a negative quantity gives a meaningless or negative figure (25A: "avg never negative").

### What the database refuses the application

`stock_movement`: SELECT and INSERT only. `stock_lot`: SELECT, INSERT and UPDATE of `qty_on_hand`, `last_movement_seq`, `negative_since`, `negative_acknowledged_at` only, so a lot's identity and acquisition cost never change. `entity_sku_cost`, `movement_sequence`: UPDATE of their counters only. Nothing is ever deleted (`InventorySchemaIntegrationTest`). Row-level security follows the template on every table (`RlsMatrixIntegrationTest` covers them by their owner column): a shop session reads and writes its own shop's rows; `entity_sku_cost` has no location, since a shop's sale moves the entity's average.

## The reads (M5-04; 25A sections 5 and 7)

`query.InventoryQueries` (`internal/availability/InventoryQueriesImpl`), read-only, in the caller's scope under row-level security:

| Query | Answer |
|---|---|
| `balances(location, sku?, includeZero)` | The lots of a location; GOOD lots with stock first, in FEFO order: `fefoRank` 1 is the lot to sell or pick first, ranked by expiry (none last), then by when it was received, then its id; DAMAGED, empty and negative lots have no rank. `GET /v1/inventory/locations/{id}/balances` (`inv.stock.view`): the unit cost only for an owner's user, never for a till or a read-only class. |
| `availability(locations, skus)` | Per requested pair: the GOOD lots with stock, never a negative lot, less the undispatched reservations of issued delivery notes (with M5-03); zero where there is nothing. `GET /v1/inventory/availability` (`inv.stock.view`). |
| `pickBatches`, `inStockBatches` | The GOOD lots with stock in FEFO order, at one location or several (M4's delivery note, M3's authoring checks). |
| `skusWithLots`, `entityAverageCost`, `lotsConsumed(grn)` | M2's assortment; the caller entity's average; whether anything but the GRN's own receipt moved a lot it received into since (M4's ReverseGrn). |

**M2's questions** (`m2catalogue.api.InventoryLotQuery`, 22A section 6) are answered by `internal/availability/LotQuestionsForCatalogue` from the lots: `hasAnyLot(sku)` (a lot of the SKU exists anywhere) and `holdsLotOf(batch, entity)`. M2 asks them in its caller's scope about lots that scope need not read, so each is a `SECURITY DEFINER` function of `V0002` that answers yes or no and shows nothing. M2's stand-in `RegistrationLotQuery` and its test were deleted in the same pull request, as M2 planned; `InventoryQueriesPostgresIntegrationTest` proves CorrectBatch against real lots (a lot holder that did not register the batch corrects it, an entity without a lot is refused).

M3 and M4 have no stub to replace: M3 on main is still the scaffold, M4 is being built by another lane and calls `InventoryQueries` directly.

## M4's events (M5-03; 25A section 6.2)

The consumers read M4's payloads as JSON and import nothing of M4: M4 depends on M5's `query` package, so a dependency back would be a cycle, and the payload is the contract (the field names of M4's `GrnConfirmed`, `DeliveryNoteIssued` and `DeliveryNoteDispatched` records, frozen at M4-05). The consumer framework delivers each event once, in the OWN scope of its owner, with no user; each consumer hands a command to a handler, which audits and publishes.

| Event | Consumer | Effect |
|---|---|---|
| `grn.confirmed.v1` | `GrnConsumer` (`m5.receipts`) → `ApplyGrnReceiptHandler` | The receiver's lots: RECEIPT of `received − damaged` GOOD and `damaged` DAMAGED per line, at the line's unit cost (the trade price), citing the GRN; the entity average re-averaged. Ownership passed when the receiver confirmed the GRN (AGENTS.md idea 2). Audit `STOCK_RECEIVED`; `stock.received.v1`. A GRN already applied is not applied again. |
| `delivery_note.issued.v1` | `DeliveryConsumer` (`m5.deliveries`) → `ReserveDeliveryHandler` | A pick list: per line, the seller's GOOD lots with free stock in FEFO order (only the named batch when the line names one), taken until the line is covered; a row with no lot for what is short. Availability subtracts the open picks. Audit `PICK_LIST_CREATED`; `pick_list.created.v1`. |
| `delivery_note.dispatched.v1` | `DeliveryConsumer` → `DispatchDeliveryHandler` | The picked units leave the seller's lots as TRANSFER_OUT citing the delivery note: in transit and still the seller's (doc 24 A-03) until the buyer's GRN; the pick list DISPATCHED, the reservation over. Audit `PICK_LIST_DISPATCHED`; `pick_list.dispatched.v1`. |

Reads: `GET /v1/inventory/receipts/{grnId}` (`inv.stock.receive`) and `GET /v1/inventory/pick-lists/{deliveryNoteId}` (`whs.pick`). The consumer-applied commands carry those codes: the system runs them with no user, so nothing is checked, and `tools/check-permissions.mjs` needs every handler's code on an operation of the slice.

## The opening balance (M5-10 at demo scope; doc 25 section 4.7, flow 6.9)

`POST /v1/inventory/opening-balances` (`inv.opening.prepare`): the counted lines of a location (batch, condition, quantity, cost), DRAFT; refused where stock has moved or another is being prepared. `/{id}/sign` (`inv.opening.sign`, MFA): SIGNED_ENTITY. `/{id}/countersign` (`inv.opening.countersign`, MFA), by another person: the entity's OPB series is registered where missing, the OPB document issued (kernel issuance, ENTITY series; M5 owns the type through `OpeningBalanceDocumentType`), the OPENING_BALANCE movements posted citing it (lots and entity average seeded), POSTED. This is how the demo loader puts stock in.

**A line can carry its batch as counted (M5-12).** Instead of `batchId`, a line may give `skuId` with `batchNo`, `expiryDate` and `printedMrp` as the item needs them; the prepare registers the batch through M2's `BatchRegistration` in the same transaction and the entity's OWN scope, with the opening balance as its origin (`OPB-<first 8 of its id>` numbers a synthetic batch). M2's own guards answer for it (an active item, an expiry or MRP where required); the same item and number give back the batch already registered. This lets staff load opening stock from the web screen (`web/src/modules/m5inventory`), where no batch exists yet before the first GRN.

**The web screens (M5-12, demo scope; doc 30 section 5.5):** `/inventory` shows a location's lots (M1's location list; item names from M2) with FEFO rank, cost when the server sends it, and the availability of each item there; `/inventory/opening/new` prepares an opening balance; `/inventory/opening/:id` signs and countersigns it.

## Tests

| Test | What it proves |
|---|---|
| `LedgerGuardsTest` | Every guard that needs no database, with its failing case. |
| `CostServiceTest` | Doc 13 scenario 3 (9,500 / 49 = 193.88); mixed receipts; the average after an oversell; 5,000 random sequences: the average never negative and within the costs of what came in, the quantity the sum of the movements; a replay gives the same row. |
| `LedgerServicePostgresIntegrationTest` | What a posting writes, audits and publishes; the average on issue and re-averaging; oversell, `lot.negative.v1` and its clearing; the lot a sale creates; dense numbering; the database guards with nothing committed; a shop session held to its shop; the ledger refused outside a command; and **balance equals the sum of movements under random interleavings**: four threads posting random receipts, sales, write-offs and count adjustments at once, then every lot equal to the sum of its movements, the entity quantity to the sum of all, the average to one replayed from scratch in commit order, and the sequence dense. |
| `InventorySchemaIntegrationTest` | The grants above; the movement partitions exist ahead of the calendar with row-level security forced. |
| `InventoryQueriesPostgresIntegrationTest` | The FEFO rank (expiry, none last; damaged and empty lots unranked), availability without negative or damaged lots and for every pair, pick order, in-stock batches, SKUs with lots, the entity average, LotsConsumed; the reads by scope; M2's questions and CorrectBatch against real lots. |
| `ConsumersPostgresIntegrationTest` | The demo chain from M4's payloads: the Federation's stock reserved FEFO by a delivery note, dispatched out of its lots, received with a damaged unit into the distributor's lots at the trade price; redeliveries applied once; a short pick; every guard with nothing committed. |
| `OpeningBalancePostgresIntegrationTest` | Prepare, sign, countersign by another person: the OPB document numbered from the entity's series, the lots and average; a second balance refused; every guard. |
| `InventoryHttpPostgresIntegrationTest` | Every operation through HTTP (the opening balance's three commands and read, the receipt and pick list reads); first the two reads: the owner's balances with batch number, expiry, rank and cost; the Federation view without the cost; another entity sees nothing; availability; a request problem. |

## Deviations from 25A, with reasons

1. **`sku_id` on `stock_lot` and `stock_movement`.** The guide keys both on the batch alone, and every query of section 7 asks by SKU (balances, availability, the entity average): without the column each would join M2's `catalogue.batch` from this schema. The SKU is copied from M2's batch when the lot is created (doc 18 B-I3: a lot's batch is of the line's SKU) and never changes; so is the batch's expiry, onto the lot (`expiry_date`), which FEFO ranks by: a batch's expiry never changes either (a correction is a replacement batch, B-I8).
2. **`movement_sequence` is a table of its own.** 25A names `SequenceService` ("dense per (location, source)") and gives it no table; a counter row per (location, source), drawn in blocks under its row lock, keeps the numbers dense without gaps from rolled-back sequences. `SequenceService` is the block draw inside `LedgerService` (only a command handler may write).
3. **Property tests without jqwik.** jqwik is not in the build; `CostServiceTest` runs 5,000 generated cases on a fixed seed (25A section 9's count), and the interleaving property runs against PostgreSQL with concurrent postings.
4. **The average on an intake when the entity holds nothing or owes stock** is the intake's cost (above); 25A writes the formula only.
5. **Tables of later tickets are not in V0001**: recipes, the policy tables, count and expiry tasks, pick lists, the loss categories and the document extensions come with the tickets that use them, each in a migration of its own, so no table exists that no code writes.
6. **Demo scope of M5-04**: the slice has the two reads the demo needs (balances, availability); the stock card (`/skus/{id}/movements`), the availability cache of 30 seconds and the PARTY callers' "live or end of day" mode (25A section 7) are deferred (`docs/PLAN_TO_M2.md`, "Deferred after the demo"). M4 reads availability in the seller's own scope, which needs none of them.
7. **Pick list lines are rows, not jsonb** (25A section 3 keeps them as jsonb): availability sums the open picks per location and SKU.
8. **The delivery note names no dispatching warehouse** (M4's `DeliveryNoteIssued` carries drops and lines only): the pick list takes each line from the seller's GOOD lots across all its locations in FEFO order. With one warehouse per seller in the demo this is the warehouse; M4 adding a `fromLocationId` would narrow it.
9. **Dispatch posts TRANSFER_OUT at the seller**, the one existing movement type for stock leaving a location in transit; 25A names only the reservation at issue. The goods stay the seller's in transit (doc 24 A-03) and the GRN receives them at the buyer. The entity quantity drops at dispatch.
10. **Quantities on a delivery note are taken in the item's base unit**; the conversion of a line's unit (M2) is deferred.
11. **The opening balance's draft and signatures live in `inventory.opening_balance`**, and the OPB document is issued on countersign (the moment 25A section 4.7 issues it), instead of the `doc_opening_balance` extension of a stored draft document. The "location ONBOARDING" guard is not applied (the demo loads active locations); the "no movements at the location" guard is. The countersignature is given in the owning entity's scope by a person other than the signer; a Federation officer countersigning from the Federation's own scope is deferred.
12. **New events** not in doc 25 section 5.3: `stock.received.v1`, `pick_list.created.v1`, `pick_list.dispatched.v1`, `opening_balance.changed.v1` (prepared and signed); each handler publishes one, as every handler must.
