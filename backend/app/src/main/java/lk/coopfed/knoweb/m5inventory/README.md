# m5inventory — M5 Inventory, Costing, Repack & Loss

The living guide of the module (AGENTS.md): where stock is and what it cost, by location, batch and condition. Once code exists, this file and the tests supersede 25A for day-to-day work; every deviation from the guide is listed at the end with its reason. Read `hello/README.md` first: its six rules apply here unchanged.

Built so far, for the demo (Phase 1: the Federation selling to a distributor): M5-01 (schema) and M5-02 (the ledger). The demo-minimal scope and what was deferred are in `docs/PROGRESS.md` and under "Deferred after the demo" in `docs/PLAN_TO_M2.md`.

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

## Tests

| Test | What it proves |
|---|---|
| `LedgerGuardsTest` | Every guard that needs no database, with its failing case. |
| `CostServiceTest` | Doc 13 scenario 3 (9,500 / 49 = 193.88); mixed receipts; the average after an oversell; 5,000 random sequences: the average never negative and within the costs of what came in, the quantity the sum of the movements; a replay gives the same row. |
| `LedgerServicePostgresIntegrationTest` | What a posting writes, audits and publishes; the average on issue and re-averaging; oversell, `lot.negative.v1` and its clearing; the lot a sale creates; dense numbering; the database guards with nothing committed; a shop session held to its shop; the ledger refused outside a command; and **balance equals the sum of movements under random interleavings**: four threads posting random receipts, sales, write-offs and count adjustments at once, then every lot equal to the sum of its movements, the entity quantity to the sum of all, the average to one replayed from scratch in commit order, and the sequence dense. |
| `InventorySchemaIntegrationTest` | The grants above; the movement partitions exist ahead of the calendar with row-level security forced. |

## Deviations from 25A, with reasons

1. **`sku_id` on `stock_lot` and `stock_movement`.** The guide keys both on the batch alone, and every query of section 7 asks by SKU (balances, availability, the entity average): without the column each would join M2's `catalogue.batch` from this schema. The SKU is copied from M2's batch when the lot is created (doc 18 B-I3: a lot's batch is of the line's SKU) and never changes.
2. **`movement_sequence` is a table of its own.** 25A names `SequenceService` ("dense per (location, source)") and gives it no table; a counter row per (location, source), drawn in blocks under its row lock, keeps the numbers dense without gaps from rolled-back sequences. `SequenceService` is the block draw inside `LedgerService` (only a command handler may write).
3. **Property tests without jqwik.** jqwik is not in the build; `CostServiceTest` runs 5,000 generated cases on a fixed seed (25A section 9's count), and the interleaving property runs against PostgreSQL with concurrent postings.
4. **The average on an intake when the entity holds nothing or owes stock** is the intake's cost (above); 25A writes the formula only.
5. **Tables of later tickets are not in V0001**: recipes, the policy tables, count and expiry tasks, pick lists, the loss categories and the document extensions come with the tickets that use them, each in a migration of its own, so no table exists that no code writes.
