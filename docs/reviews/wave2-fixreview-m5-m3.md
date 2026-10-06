# Wave 2 fix review: M5 inventory and M3 pricing

Second opinion on `wave2-m5-m3.md` before any fix is built. Code read at main 91e550d8 (branch `review/wave2-findings`), 6 October 2026. Read only; nothing run (Docker down). Every verdict below rests on my own reading of the cited code, the seeds, the tests, docs 23/23A/25/25A, the M1/M3/M5 READMEs and `docs/DECISIONS_PENDING.md` (doc 10 v0.4).

Paths are under `backend/app/src/main/java/lk/coopfed/knoweb/` unless they start with `backend/shared-engine`, `backend/app/src/main/resources`, `backend/app/src/test` or `web/`.

## Summary

| Finding | Sev. | Valid? | Verdict | Size | Migration? | Fix group |
|---|---|---|---|---|---|---|
| M5-01 expiry never checked in picks and availability | high | YES (wider than stated: pricing candidates too) | DECIDE THEN BUILD | M | no (config seed) | A |
| M5-02 short pick rows never re-resolved at dispatch | med | YES | BUILD AMENDED | S | no | D |
| M5-03 approved transfer request sent short, silently | med | YES | DECIDE THEN BUILD | S | no | D |
| M5-04 voids/refunds never restore stock; till bundles ignored | med | YES (recorded deferral) | DECIDE THEN BUILD | M | no | G |
| M5-05 unresolved sale line only an audit row | low | PARTLY (stock is not overstated) | BUILD AMENDED | S | no | G |
| M5-06 entity average mis-weighted around transfers | med | YES (wider than stated) | BUILD AMENDED | S | no | B |
| M5-07 negative-lot crossing overwritten in one posting | low | YES (settled by trace) | BUILD AS SUGGESTED | S | no | B |
| M5-08 cost row lock serialises sales per SKU | low | YES | DEFER | – | – | – |
| (unnumbered) oversold units costed at 0 | low | YES (documented) | DEFER | – | – | – |
| M5-09 approval limits never enforced, fail open | high | YES (worse: limits cannot even be set) | DECIDE THEN BUILD | M | yes (m1security) | C |
| M5-10 witness and approver may be one person | med | PARTLY (design allows it literally) | DECIDE THEN BUILD | S | no | C |
| M5-11 failed photo upload blocks a write-off for ever | low | YES (settled by reading) | BUILD AMENDED | S | no | C |
| M5-12 back-office "holds the quantity" checks unlocked | med | YES (not for counts) | BUILD AS SUGGESTED | S | no | D |
| M5-13 count variance measured at submit, not at counting | med | YES | DECIDE THEN BUILD | M | yes (m5inventory) | E |
| M5-14 tolerance by quantity only, no value cap | med | YES | DECIDE THEN BUILD | S | no (config seed) | C |
| M5-15 no short receipt, no cancel of a transfer | med | YES (recorded deferral) | DEFER (before pilot) | L | yes | – |
| M5-16 one person may issue and receive a transfer | low | YES (the demo loader does it) | BUILD AMENDED | S | no | C |
| M5-17 repack cost not conserved | med | PARTLY (rounding immaterial; reversal real) | BUILD AMENDED | M | no | B |
| M5-18 no guard on repack yield | med | YES | DECIDE THEN BUILD | S | no (config seed) | C |
| M5-19 opening balance: preparer may sign; batches at prepare | low | PARTLY | DEFER (cutover, P-11) | M | yes, when built | – |
| M5-20 ledger property test thin | low | PARTLY | BUILD AMENDED (folded into B) | S | no | B |
| M5-21 web sends quantities as JSON numbers | low | NO | INVALID | – | – | – |
| M3-01 zero-benefit line rule blocks a real one | med | YES | BUILD AS SUGGESTED | S | no | A |
| M3-02 markdown on the wrong batch; matches expired | med | YES | DECIDE THEN BUILD | M | no | A |
| M3-03 control price can be backdated | med | YES | DECIDE THEN BUILD | S | no (config seed) | F |
| M3-04 new version drafted from a superseded one | low | PARTLY (design-intended) | DEFER (web warning) | S | no | – |
| M3-05 publish does not lock the draft | low | YES (settled by reasoning) | BUILD AS SUGGESTED | S | no | F |
| M3-06 central quote ignores rules | low | NO (documented shelf price) | INVALID | – | – | – |
| M3-07 FIXED_PRICE 0 accepted | low | YES | DECIDE THEN BUILD | S | no | A |
| M3-08 ceiling change-log to tills deferred | low | YES (recorded deferral) | DEFER (before pilot) | M | likely | – |
| M3-09 tests read the date once | low | YES (settled by reading) | BUILD AMENDED | S | no | F |

Counts (30 numbered findings): BUILD AS SUGGESTED 4, BUILD AMENDED 8, DECIDE THEN BUILD 11, DEFER 5 (plus the unnumbered one), INVALID 2.

Next free migration numbers at 91e550d8: `m5inventory/V0007`, `m1security/V0018`. Check open PRs before you take one.

## Fix groups

Work order: A and C first (both high findings), then D, B, F, E, G. A and C each need a decision before code. B touches `CostService`, so do it in one PR so the cost tests change once.

### Group A: expiry-aware stock and pricing (M5-01, M3-02, M3-01, M3-07)

Decide first: D1 (the expiry rule) and D4 (which batch a markdown follows). M3-01 needs no decision and can go first on its own.

**M5-01** [high]. Valid: YES. `RANKED_LOTS` ranks by `expiry_date nulls last` with no date filter (InventoryQueriesImpl:40-50); `availability` (:76-94), `lockCandidates` (ConsumerStore:40-62), `pickBatches`/`inStockBatches` and the sale fallback (ApplySaleHandler:170-182) never compare expiry with a date. The finding misses one effect. `inStockBatches` is also the pricing candidate list (`InStockBatchesForPricing`), so an expired lot with an older, **lower printed MRP** stays a candidate under AUTO_LOWEST and holds the shelf price down for every unit. In Sri Lanka older batches usually carry lower MRPs.
Suggested fix: NEEDS CHANGES. "Rank last and flag" is the wrong option. An expired lot must not be picked, transferred, counted as available or used as a price candidate. Built fix:
- Pass the **business date** (Asia/Colombo, from `Clock` plus `coop-erp.business-timezone`, as `PriceListRules.today` does) into the queries. Do not use SQL `current_date`: the session runs in UTC, so between 00:00 and 05:30 Colombo it gives yesterday.
- Exclude `expiry_date < :date` from the `fefo_rank`, from `availability`, `lockCandidates`, `pickBatches` and `inStockBatches`. Expired lots stay visible in `balances` with their rank null, so they still show on the stock book with an "expired" badge.
- Outbound picks (`lockCandidates` for delivery notes) also skip lots with less than `inventory.dispatch_min_shelf_life_days` left (new config item, ENTITY scope, default 0, set by `inv.policy.set`). Transfers inside one entity use expiry only, because sending short-dated stock to a busier shop is how a society clears it.
- The sale fallback keeps expired lots (a sale is a fact and the lot is where the units came from) but writes `SALE_OF_EXPIRED` (REVIEW) when the batch it posts against is past expiry on the receipt's date.
- Expired stock leaves through the write-off lifecycle (category EXPIRED). The nightly `ExpiryScanJob` (25A section 7) that drafts those write-offs is a separate ticket and is not needed for this fix.

No migration. The new config item needs en/si/ta descriptions in `seed/kernel/config-items.yaml`. Tests: one per query plus the midnight edge (a lot that expires "today" is still sellable today). Risk: medium. The demo data must not hold already-expired lots that the demo expects to pick. Check `DemoShopStock` expiry dates against the demo date.
Business: see D1.

**M3-02** [med]. Valid: YES. `matches` (RuleEvaluator:68-73) uses `r.batch`, which under AUTO_LOWEST is the lowest-MRP candidate (PriceResolver:47), not the unit sold. `DAYS.between(date, expiry) <= D` is true for negative days, so an expired batch gets the markdown. Doc 23 section 3.5 says "EXPIRY_MARKDOWN applies only to the specific batch". A side effect: the receipt line stamps that lowest-MRP batch, so M5's SALE depletes the lowest-MRP lot, not the FEFO lot. That drifts the per-batch book and makes expiry write-offs wrong.
Suggested fix: NEEDS CHANGES (per D4). In the shared engine:
1. Candidates are `onHand > 0 && (expiry == null || expiry >= date)`. This fixes the till and central together, since both use the same engine.
2. The markdown is evaluated on the identified batch (BARCODE_RESOLVED scan or PICKER pick), else on the FEFO-first candidate (earliest expiry with stock).
3. It matches only when `0 <= days <= D`.
4. When a markdown applies, stamp that batch on the line (`LineResult.batch`). `mrpApplied` stays the policy's MRP: the base price is still min(list, policy MRP, control), which never charges above the lowest printed MRP on the shelf.

Bump `ENGINE_VERSION` (0.1.0 to 0.2.0) because results change. Add unit tests in shared-engine. Risk: medium. The till must ship the same engine version, and blocking the sale of an identified expired batch at the till belongs to the till area (cross-reference TWK).

**M3-01** [med]. Valid: YES. `choose` runs over all matching rules; the bill path filters `benefit > 0` and the line path does not. Fix CORRECT: `applicable.filter { lineDiscount(it, r) > Money.ZERO }` before `choose`. It is also the best business answer: a priority orders rules that give something, and a rule that gives the customer nothing must not take the customer's discount away. One line plus a unit test. Risk: low. Ship it with the engine version bump of M3-02.

**M3-07** [low]. Valid: YES. `checkBenefit` allows FIXED_PRICE 0 deliberately (RuleVocabulary:139), and PERCENT_OFF 100 is allowed too (:135). Both make an item free through a rule. That bypasses the write-off controls: two signatures, a witness and value bands for giving goods away. Fix per D11: FIXED_PRICE > 0 and PERCENT_OFF < 100. Giving goods away is a DONATION/SAMPLES write-off or a FREE_ITEM rule. At activation, a FIXED_PRICE at or above the current list price returns a warning, not a refusal. S, no migration. Risk: low; check the rule seeds and demo rules for 0 or 100.

### Group B: ledger cost and crossing correctness (M5-06, M5-07, M5-17, M5-20)

No decision needed beyond D10 (shared with M5-04). Do it in one PR: `CostService`, `MovementType`, `CostServiceTest` and the ledger property test.

**M5-06** [med]. Valid: YES, and wider than stated. `TRANSFER_OUT` lowers the entity quantity at the average and `TRANSFER_IN` adds it back without re-averaging (`reaverages()` excludes it; CostService:47-48). Every intake that lands while stock is in transit is therefore averaged against too small a base, not only when the holding reaches zero. Example: 200 at 10 (two warehouses), transfer 100, GRN 100 at 20. The average becomes 15 and stays 15 after receipt, where the true figure is 13.33.
Suggested fix: NEEDS CHANGES. Option 1 (keep in-transit in the entity row) breaks the invariant the property test asserts, entity qty = sum of movements (LedgerServicePostgresIntegrationTest:327-330). Build option 2: `TRANSFER_IN` re-averages at its own cost, which is `transfer_line.unit_cost`, the TRANSFER_OUT's `unit_cost_at_movement` (IssueTransferHandler:175). Value is then conserved exactly: the out takes q×a, the in returns q×a, and the average after receipt equals the average of a pool that had kept the transit stock. Change `MovementType.reaverages()` to `carriesItsOwnCost`, update `CostServiceTest.mixedReceiptsAverageByQuantity` (it asserts the old rule at :48-53) and stop skipping TRANSFER_IN in the random test. Cross-entity delivery TRANSFER_OUT never comes back, so it is unaffected. Existing averages are not repaired (demo data only). S, no migration. Risk: low.

**M5-07** [low]. Valid: YES, settled by trace of LedgerService:255-263. With the lot at -3 and a posting of +5 then -4: CLEARED is put, then `crossings.put(key, WENT_NEGATIVE)` overwrites it. Result: a second `lot.negative.v1`, a fresh `negative_since` (the 7-day `NegativeLotReviewJob` clock restarts), and the earlier acknowledgement is kept against a "new" negative period. Fix CORRECT: compare the lot's state when locked with its state at the end of the posting. If negative before and after, keep the original `negative_since` and record nothing. If it was negative only before, record CLEARED. If only after, record WENT_NEGATIVE. S. Add the case to the ledger test.

**M5-17** [med]. Valid: PARTLY.
- Rounding half: real but immaterial, and the finding overstates it. The output cost is rounded to 4 dp, so the error is at most 0.00005 × output qty: Rs 0.05 per 1,000 packs, not "over a rupee". Every average-cost movement in the ledger rounds the same way. Accept it and record it in the README; do not add a remainder column.
- Reversal half: real (ReverseRepackHandler:86-107). REPACK_CONSUME of the output is costed at the output item's **current entity average**, while the input returns at its original cost. If the output item had other stock at another cost (a second recipe, an earlier repack), the reversal leaves q×(output cost − average) stuck in the output item's value even immediately after the repack.

Fix (amended): give `CostService` an explicit "out at a given cost" rule: `avg' = (Q·a − q·c)/(Q − q)` when `Q − q > 0`, else keep the last average, clamped at ≥ 0. Use it for the reversal's REPACK_CONSUME with `c = repack.output_unit_cost`. It is the same rule M5-04's SALE_REVERSAL and a future GRN_REVERSAL at cost need. M, no migration. Risk: medium (cost arithmetic); covered by the property test. Low priority.

**M5-20** [low]. Valid: PARTLY. The replay is a self-comparison and cannot catch a wrong rule. It does catch lost updates under concurrency, and the rules are covered by `CostServiceTest`'s hand-computed cases. Fold into this group: add a second location with TRANSFER_OUT/TRANSFER_IN pairs (M5-06), a crossing check (`negative_since is null` iff `qty_on_hand >= 0`, M5-07) and a REPACK pair (M5-17). No standalone PR.

### Group C: approval and witness controls (M5-09, M5-10, M5-11, M5-14, M5-18, M5-16)

Decide first: D2 (limits), D5 (witness), D6 (count value cap), D8 (yield). M5-11 and M5-16 need no decision.

**M5-09** [high]. Valid: YES, and worse than reported. `requireWithinLimit` fails open (ControlPolicy:97-124), and in addition **no limit can be configured at all**:
- `inv.writeoff.approve` and `inv.adjust.approve` have no `limits_schema` in `seed/m1party/permissions.yaml`.
- M1 refuses limits on a permission without one (`m1.role.limits_not_accepted`, RoleGuards:124-132; M1 README line 117: "today no permission takes limits").
- `M1SeedLoader` inserts permissions `ON CONFLICT DO NOTHING` and never writes `limits_schema`.
- The only test of the limit (WriteOffPostgresIntegrationTest:264) writes the limit by SQL past M1.

Also: `WriteOffStore.value` values a SKU with no cost row at 0.00, so any quantity of zero-cost stock (free-issue bonus goods, samples) is band 1.
Suggested fix: NEEDS CHANGES. Built fix (per D2):
1. A migration `m1security/V0018__approval_limits.sql` that sets `limits_schema = {"properties":{"max_value":{"type":"number","minimum":0}},"required":["max_value"]}` on both permissions. Editing the YAML is not enough: the seeder never updates an existing row. Also add the schema to the YAML for fresh databases.
2. `ControlPolicy.requireWithinLimit`: a grant without `max_value` (seeded templates, legacy roles) approves up to `inventory.approval_band1_limit` only. No matching assignment means no authority: refuse with `m5.approval.limit_exceeded`, limit 0. Take the highest `max_value` across the user's assignments at the entity.
3. Value a line at the entity average; if that is zero, at the lot's `unit_cost`. If that is also zero, the line counts as band 2 for routing, so it is never self-approved within band 1.
4. Seed explicit `max_value` on the demo approver roles in `seed/m1security/demo-users.demo.sql`.

Same code path for `ApproveAdjustmentHandler:94`. M, one migration. Risk: medium. Demo write-offs or adjustments above Rs 25,000 by an unconfigured role start failing, which is intended; the demo seed must carry limits.

**M5-10** [med]. Valid: PARTLY. Doc 25 section 3.8 requires only witness ≠ requester and approver ≠ requester, and the Javadoc chose "the witness may also approve" deliberately (README deviation 18). At a multi-staff location this collapses the write-off, "the highest-fraud-risk lifecycle" (doc 25 section 9.4), to two people. Fix per D5: in `ApproveWriteOffHandler`, refuse `witness_user_id == scope.userId()` unless `remote_witness` is true (new `m5.writeoff.approver_is_witness`). The header already carries both fields. No SoD seed: an INSTANCE pair on witness/approve would also forbid the remote case. S. Risk: low. Add the problem id's translations.

**M5-11** [low]. Valid: YES, settled by reading. `AddWriteOffPhotoHandler` is DRAFT-only (:67) and always presigns a new attachment (null id), so a PENDING upload can never be renewed after submit. The kernel turns a never-uploaded object FAILED (Attachments.java:16-19). `WitnessWriteOffHandler:91-94` then refuses for ever, and the only exit is reject and redo. Fix (amended): the witness accepts when no photo is PENDING, every photo is COMPLETE or FAILED, and at least one is COMPLETE where `photosRequired`. FAILED ones are listed in the WRITEOFF_WITNESSED audit. Evidence stays fixed after issue, so photos are not added later. S. A runtime test would confirm the FAILED transition timing (an upload never sent, the verifier run after expiry).

**M5-14** [med]. Valid: YES. `withinTolerance` is quantity-or-percent (ControlPolicy:75-81). In-tolerance lines post at submit (SubmitCountHandler:173-185) with no value check and no ceiling on the total. A FULL count accepts any batch M2 knows, so a lot with book 0 can gain up to 2 units unreviewed. The design also asks that "every tolerance-cleared variance lands on the exception report" (doc 25 section 9.4); none does. Fix per D6:
- A line auto-posts only if it is within quantity/percent **and** its value is ≤ `inventory.count_tolerance_value` (default Rs 1,000).
- If the auto-posted total of the count exceeds `inventory.count_autopost_value_cap` (default Rs 10,000), every line goes to review.
- A surplus on a lot whose book was ≤ 0 always goes to review.
- Each auto-posted line is listed in a `COUNT_TOLERANCE_CLEARED` REVIEW audit (the exception report reads it).

Config seed only (en/si/ta). S. Risk: low.

**M5-18** [med]. Valid: YES. ExecuteRepackHandler:108,165-169 checks only scale and sign. The output cost is spread over whatever quantity is typed (50 kg into 5,000 packs makes 5,000 sellable packs at Rs 1.90). Fix per D8: new `inventory.repack_yield_tolerance_pct` (ENTITY, default 2). Over-yield beyond expected × (1 + tol) is refused (`m5.repack.yield_out_of_range`): this is a back-office command, not a till fact. Under-yield beyond tolerance needs a reason and writes `REPACK_YIELD_EXCEPTION` (REVIEW). A future till repack bundle applies and flags instead of refusing. S, config seed. Risk: low.

**M5-16** [low]. Valid: YES (ReceiveTransferHandler:74 lets an entity-wide session receive; `issued_by` is never compared). Refusing breaks the demo: `DemoDataLoader:659-667` issues and receives the Hettipola transfer as `M101_MANAGER`. Inside one entity a self-received transfer does not change the entity's stock; the risk is hiding a loss in transit, which the shop's count later shows. Fix (amended): allow it, but write `TRANSFER_SELF_RECEIVED` (REVIEW) when `received_by == issued_by`. Do not refuse. S. Risk: low.

### Group D: outbound picks, shortfalls and locking (M5-12, M5-02, M5-03)

Decide first: D9 (partial fill of a request). M5-12 needs no decision; do it first.

**M5-12** [med]. Valid: YES for write-off approval (`WriteOffStore.onHand`) and transfer issue (`TransferStore.goodOnHand`); both are plain selects. NO for counts: a count variance is a delta, so a sale committed between the read and the post still leaves the lot at counted − sold, which is physically right. Fix CORRECT, with one addition: lock the lots with `FOR UPDATE`, **sorted in the ledger's `LotKey` order (location, batch, condition)**, so two multi-line approvals cannot deadlock. The ledger then re-locks the same rows in the same order. RepackStore:108-118 is the pattern. Do not add a per-type "may go negative" flag to the ledger: the handler lock is explicit and keeps the ledger simple. Also lock in the approval path of `TransferRequestConsumer` through `IssueTransfer` (same store method). S. Risk: low.

**M5-02** [med]. Valid: YES. DispatchDeliveryHandler:79-94 moves only `lotPicks`; short rows are never revisited. The realistic case is stock that arrives by GRN between the delivery note's issue and the vehicle leaving: the shipped units then stay on the seller's book. Suggested fix: NEEDS CHANGES. Do not post the remainder negative. If the vehicle carried more than the book held, the extra came from unrecorded stock, and a negative posting would double-count it. Built fix: at dispatch, re-pick the short rows FEFO from current free stock (`lockCandidates`, honouring M5-01); post TRANSFER_OUT for what is found; record any remaining quantity in `PICK_SHORT_DISPATCHED` (REVIEW) with the delivery line and quantity. The buyer's GRN discrepancy settles the commercial side. S-M. Risk: low.

**M5-03** [med]. Valid: YES (TransferRequestConsumer:50-70; unique `transfer_by_request`). M4 checks availability at approval, so this happens only in the gap between approval and the consumer. Fix per D9: keep one transfer per request (the index stays). The consumer records `TRANSFER_REQUEST_SHORT` (REVIEW) with sku, wanted and sent per short or dropped line. `TransferIssued` gains an additive `shortLines` count, so M4 or the screen can show "partly filled". The shop raises a new request for the rest. S, no migration. Risk: low; the event change is additive.

Additional observation (not in the findings): `pickBatches` and `IssueTransfer`'s `goodOnHand` ignore open pick-list reservations. A transfer can take units already reserved for a delivery note, and the dispatch then takes the lot negative. Fold the subtraction of open picks into the same queries when Group A changes them.

### Group E: counting while trading (M5-13)

Decide first: D7.

**M5-13** [med]. Valid: YES. SubmitCountHandler:141 reads the book at submit, so sales between counting a shelf and pressing submit show as a surplus and auto-post when within tolerance. The code follows doc 10 E-10 ("movements during the count adjust the expectation") literally; the gap is per line. Fix per D7:
- Migration `m5inventory/V0007__count_line_counted_at.sql` adds a nullable `count_line.counted_at timestamptz`.
- The web form stamps each line when it is entered; the request carries it (OpenAPI change).
- book(line) = `qty_on_hand` − Σ `qty_delta` of the lot's movements with `occurred_at > counted_at`. Use `occurred_at`, not `received_at`, so till sales uploaded late still count on the right side.
- A line with no `counted_at` keeps today's rule.
- The till bundle already carries `expectedQty` per line (25A section 7.4), which is trusted and re-checked.

M. Interim mitigation, if Group C goes first: positive in-tolerance variances on a lot that had any SALE since the count started go to review (M5-14's surplus rule covers most of it). Risk: medium (contract change on the count request).

### Group F: pricing hygiene (M3-03, M3-05, M3-09)

Decide first: D3.

**M3-03** [med]. Valid: YES (EnterControlPriceHandler:97-127 has no date check and rewrites `effective_to` of the superseded row; Rescind refuses the past). Fix per D3:
- Accept `effectiveFrom` back to today − `pricing.control_price_backdate_days` (new config item, FEDERATION scope, default 7). This matches gazette practice: a price order often takes effect on publication, before the steward can enter it.
- Refuse anything older (`m3.control_price.effective_from_past`).
- When `effectiveFrom < today`, record the audit as REVIEW (`CONTROL_PRICE_RETROSPECTIVE`) with the superseded row's previous `effective_to` in `before`. Today `before` is null, so the old end date is lost.
- The table stays the legal history (effective time); `entered_at` and the audit row are the knowledge time.

S, config seed. Risk: low.

**M3-05** [low]. Valid: YES, settled by reasoning. Under READ COMMITTED the second publish blocks on the row lock, then its `update ... where price_list_id = ?` (no status condition) runs again. That gives two audits and two `price_list.published.v1`, and the second `apply_from`/`published_at` win. Fix CORRECT: `select ... for update` on the list row in the guard (`PriceListStore.find` variant), then re-check DRAFT. S. Runtime check: two threads publish the same draft behind a latch; today both succeed.

**M3-09** [low]. Valid: YES (five test classes keep `LocalDate.now(Asia/Colombo)` in a field). Flaky only across 18:30 UTC. Fix (amended): pin the time in those classes, either through the kernel's `HistoricalTime.at(...)` if it is enabled in tests, or a `@TestConfiguration` fixed `Clock` for the class. Derive `today` from that clock. Test only, S.

### Group G: till facts not yet applied (M5-04, M5-05)

Decide first: D10. After the demo (demo priority).

**M5-04** [med]. Valid: YES (recorded deferral, M5 README "Voids and refunds (SALE_REVERSAL) are deferred"). Fix per D10:
- A `SaleReversalConsumer` on `receipt.voided`/`receipt.refunded` posts SALE_REVERSAL to the original SALE's lot, condition GOOD, at the **original SALE movement's `unit_cost_at_movement`**. This changes `SALE_REVERSAL` to carry its own cost and re-average. It is declared and never posted, so no data is affected.
- Use DAMAGED once M6's refund carries a returned condition (it carries none today).
- A small consumer for `count.recorded`, `writeoff.requested`, `repack.executed` and `transfer.issued` bundles writes `TILL_FACT_NOT_APPLIED` (REVIEW) until each has its hook.

M. Risk: medium (idempotency: cite the void/refund document id and skip when movements exist, as ApplySale does).

**M5-05** [low]. Valid: PARTLY. "Stock stays overstated" is wrong: the shop held no lot of the item, so the book is 0, and the units were unrecorded stock; book and shelf agree afterwards. The real gap: the sale never reaches the ledger, so cost of goods sold is missing and the negative-lots screen never shows it. A new table (the suggested fix) is not needed. Fix (amended): when the shop has no lot and the till's batch is unknown, post against the SKU's newest batch known to M2 (`BatchQueries` by SKU). The ledger creates the lot negative, which writes `SALE_WITHOUT_LOT` and `lot.negative.v1` and puts it on the existing acknowledge flow. Keep `SALE_LINE_UNRESOLVED` only when the SKU itself is unknown. S. Risk: low.

### Deferred, with the reason

- **M5-08** [low]: valid. One row per (entity, SKU), locked for a few milliseconds per posting; an MPCS has about 5 shops (up to about 30). Measure in the 25A performance test (1,000 receipt bundles per second) before changing anything.
- **Unnumbered, cost on negative stock** [low]: valid and documented. Movements are immutable, so re-costing oversold units needs a cost-correction movement type. Add a caveat to the M8 margin report; revisit with M8.
- **M5-15** [med]: valid; the M5 README lists "cancel before receipt, a short receipt with its ADJ draft" as deferred. Build before shops receive transfers at pilot: per-line received quantity on `transfer_receipt` (new line table, migration), a shortfall booked as an in-transit loss (write-off category DAMAGED_IN_TRANSIT at the entity), and `CancelTransfer` posting TRANSFER_IN back at the source. L.
- **M5-19** [low]: partly valid. "Preparer may sign" is not a gap: doc 25 section 3.7 asks for two signatures, the entity's and the Federation's, and the demo loader prepares and signs as one user (DemoDataLoader:508-512). The real issues are elsewhere:
  - The countersigner can be from the same entity (README deviation 11, recorded).
  - `RegisterBatch` at prepare: a typo'd expiry on a discarded draft becomes a permanent batch. Re-preparing with the same batch number "answers the batch already registered" (RegisterBatchHandler:28) with the wrong expiry, and the entity cannot correct it because it holds no lot.

  Build both before the cutover exercise (doc 10 P-11): store batch number, expiry and MRP on `opening_balance_line` (migration) and register at countersign; countersign in the Federation's scope. M.
- **M3-04** [low]: design-intended (23A line 500: source PUBLISHED or SUPERSEDED; going back to an old version is a legitimate undo). Add a web warning and a line diff when the source is not the latest. No backend change.
- **M3-08** [low]: valid, recorded in the handler Javadoc. Central always caps; the till's snapshot does not until M3-09 (snapshot contributor) is built. A legal risk: build it before any pilot till prices from a snapshot.

### INVALID

- **M5-21**: every value the server accepts (`numeric(14,3)` and `numeric(14,4)`, at most 14 significant digits) round-trips exactly through a JS double and `JSON.stringify`'s shortest form. Jackson reads the token text straight into `BigDecimal`. Only input the server must refuse anyway (16+ digits) can be altered. A runtime check would settle it: post `99999999999.999` and `0.001` from `RepacksPage`/`stockView.lineOf` and read the stored values. (Minor, not a precision issue: `Number("0x10")` parses as 16. Harmless.)
- **M3-06**: documented behaviour, not a defect. `PricingQueries.resolveRetailPrice` Javadoc, `RetailPrice` Javadoc and the OpenAPI summary (m3pricing.yaml:227-232) all say "Rules are not applied": it is the shelf price, not a receipt. The finding's own first option ("say so in the API") is already done.

## Decisions to record

Each is written so it can be recorded "accepted on the architect's delegation" if the architect agrees. None is accepted yet.

**D1. Expired and short-dated stock (M5-01, M3-02).**
- Decision:
  - A lot is expired when its expiry date is before the business date (Asia/Colombo). Stock is sellable through the printed date.
  - Expired lots are never picked for delivery or transfer, never counted as available, and never price candidates.
  - They leave by write-off (EXPIRED), or by return to the supplier where the trade terms allow.
  - Short-dated stock is not excluded inside an entity. It enters the markdown window (`inventory.expiry_warning_days`, doc 25 section 7: 30 days, dairy 7, fresh 3, set by the entity) and M3's EXPIRY_MARKDOWN rules, which the MPCS authors and funds.
  - For delivery notes to another entity, the seller's `inventory.dispatch_min_shelf_life_days` (ENTITY, default 0, Federation may set a default) excludes lots with less life left. The seller decides its cut-off; the buyer's protection is the GRN discrepancy.
  - "Expired" is derived from the date at query time. There is no quarantine condition and no movement at midnight.
- Rejected:
  - Rank last and flag: expired goods still ship when nothing else is there.
  - A new QUARANTINE/EXPIRED lot condition moved by a nightly job: schema change, extra movements and a job, for what a date comparison gives.
  - A single Federation-wide shelf-life minimum: dairy and rice differ by months.
- Why: selling expired food is an offence that public health inspectors enforce under the Food Act (confirm the wording with Federation legal, L-items); an older expired batch with a lower MRP also distorts AUTO_LOWEST prices.

**D2. Write-off and adjustment approval limits (M5-09).**
- Decision:
  - The approver's authority is the role grant's `max_value` (doc 25 section 7: "writeoff_bands as role limits (M1)"), and the permission requires one for new grants.
  - A grant with no `max_value` approves up to the band-1 limit only.
  - No grant at the write-off's entity means no authority.
  - The value is the entity average (doc 10 A-05); a zero average falls back to the lot cost, and a zero-cost line routes to band 2.
  - Band 1 (≤ Rs 25,000): society accountant or branch manager. Band 2 (≤ Rs 250,000): general manager. Band 3: chairman, recording the board resolution reference in the approval reason.
  - The two figures stay ENTITY configuration items.
- Rejected:
  - Keep "no limit = unlimited" (today's fail-open).
  - Enforce bands from config only, ignoring role limits: one approver could approve every band.
  - A separate permission per band: three codes, against the limits mechanism doc 19 designed.
- Why: fail closed on the highest-fraud-risk lifecycle (doc 25 section 9.4), while small write-offs keep working without configuration.

**D3. Backdated control prices (M3-03).**
- Decision: `effectiveFrom` may be up to `pricing.control_price_backdate_days` (Federation config, default 7) before the business date, to match the gazette's own effective date. Such an entry is a REVIEW audit carrying the superseded row's previous end date. Anything older is refused and needs a CR-level correction.
- Rejected:
  - Refuse all past dates: the system would record a ceiling effective later than the law says, and compliance reports would understate overcharges.
  - Allow any past date: it rewrites history silently.
- Why: Consumer Affairs Authority price orders take effect from the gazette's date, usually before the steward (doc 10 H-05) can enter them. The table is effective-time history; the audit is knowledge time.

**D4. Which batch an expiry markdown follows; precedence (M3-02, M3-01).**
- Decision:
  - A markdown applies to the identified batch (scanned under BARCODE_RESOLVED, picked under PICKER), else to the FEFO-first in-date batch with stock at the till. The line is stamped with that batch so M5 depletes it.
  - A markdown never applies at days < 0.
  - Precedence stays the stacking policy (one line rule, PRIORITY_THEN_BEST) after removing zero-benefit rules. The rule screen defaults EXPIRY_MARKDOWN to the top priority.
- Rejected:
  - The lowest-MRP batch (today): discounts the wrong units.
  - Markdown only when the batch is scanned: most Sri Lankan packs carry a GTIN without a batch, so markdowns would never fire under AUTO_LOWEST.
- Why: the near-expiry lot is front of shelf under FEFO, and the lot snapshot stops the markdown when that lot sells through.

**D5. Witness distinct from approver (M5-10).**
- Decision: at a location that is not single-staff, the approver must differ from the in-person witness. At a single-staff location the remote witness (who must hold approve) may also approve.
- Rejected: three distinct people everywhere (impossible at a one-person shop with a one-accountant society); today's rule (two people suffice anywhere).
- Why: keeps three people where three exist; the single-staff path keeps photos as the third eye.

**D6. Count tolerance by value (M5-14).**
- Decision: auto-post only within quantity/percent **and** within `inventory.count_tolerance_value` per line (Rs 1,000) and `inventory.count_autopost_value_cap` per count (Rs 10,000). A surplus on a lot with book ≤ 0 is always reviewed. Every tolerance-cleared line goes on the exception report.
- Rejected: per-SKU/tag tolerance tables (25A; deferred by README deviation 15); value-only tolerance (pennies of fast movers would all escalate).

**D7. Expectation per counted line (M5-13).**
- Decision: each count line carries `counted_at`; its book is the lot as at that moment (movements after `counted_at` by `occurred_at` are excluded). This refines doc 10 E-10 and does not change it.
- Rejected: freezing counted items at the till (stops trade); "count after closing" by procedure only (unenforceable; shops trade long hours).

**D8. Repack yield tolerance (M5-18).**
- Decision: `inventory.repack_yield_tolerance_pct` (ENTITY, default 2) around the recipe's expected output. Over-yield beyond it is refused on the web. Under-yield beyond it needs a reason and is flagged REVIEW. Till bundles flag and never refuse.
- Why: over-yield creates sellable stock from nothing; under-yield is a real loss that must be visible.

**D9. Partial fill of an approved transfer request (M5-03).**
- Decision: a request is filled once. What could not be sent is recorded (REVIEW audit, `shortLines` on the event) and shown as "partly filled". The shop raises a new request for the rest. This matches an indent book.
- Rejected: keeping the request open for the remainder (needs a relaxed unique index and a backorder engine for a rare race).

**D10. Cost of a sale reversal (M5-04, with M5-17).**
- Decision: a void or refund returns the units to the original lot at the original SALE movement's unit cost and re-averages. A reversal-at-cost out movement removes value at its own cost. Both are new `CostService` rules.
- Rejected: the entity average at the moment of the void: a void must undo the sale's cost exactly.

**D11. Free goods through a price rule (M3-07).**
- Decision: FIXED_PRICE must be above zero and PERCENT_OFF below 100. Giving goods away is a write-off (DONATION, SAMPLES) or a FREE_ITEM rule. A fixed price at or above list warns at activation.
- Rejected: allowing zero with a second approval (duplicates the write-off controls in M3).

## Findings I could not settle

All ten PLAUSIBLE findings were settled by reading as far as reading can go. Where a runtime test would add certainty:

- **M5-07**: settled by trace. To confirm: post -3 on a lot, then one posting of +5 and -4; expect today one `STOCK_LOT_NEGATIVE` and a new `negative_since`.
- **M5-11**: settled. To confirm: presign a photo, never upload, run the attachment verifier after expiry, then witness; expect `m5.writeoff.photos_incomplete` with no way forward.
- **M3-05**: settled by READ COMMITTED semantics. To confirm: two threads publish one draft behind a `CountDownLatch`; expect two `PRICELIST_PUBLISHED` audits today.
- **M3-09**: to confirm, run `PriceListHandlersPostgresIntegrationTest` with the system clock at 23:59:59.9 Asia/Colombo.
- **M5-21**: to confirm, the round-trip described under INVALID.
- **M5-05, M5-15, M5-16, M5-19, M5-20**: settled by reading (handlers, demo loader, README deferral list, test body); no runtime test needed.
- **M5-06 magnitude on real data**: not testable until Docker is up. Compare `entity_sku_cost.avg_cost` with a from-scratch replay that treats transfer pairs as neutral on a demo database, to size the existing skew (no repair planned).
