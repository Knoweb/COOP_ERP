# Wave 2 fix review: M6 till ingest and receipt screens, M8 reporting, M1 administration

Reviewer: Claude Opus 5.5, independent second opinion before any fix is built. Code read at main 91e550d8 (working tree `review/wave2-findings`). Input: `docs/reviews/wave2-m6-m8-m1admin.md` (27 live findings; M8-01 and M1A-02 dropped and not reviewed here). Cross-reference: RLS-08 in `docs/reviews/wave2-rls.md`. Read only; no build, test or Docker run.

Paths are under `backend/app/src/main/java/lk/coopfed/knoweb/` unless they start with `backend/app/src/main/resources/`, `web/`, `till/` or `docs/`.

## Summary

| Finding | Sev. | Valid? | Fix verdict | Size | Migration? |
|---|---|---|---|---|---|
| M6-01 | medium | YES | BUILD AMENDED (validate at the kernel gateway, use `kernel.sync_quarantine`, no M6 quarantine table) | M | no |
| M6-02 | medium | YES | BUILD AMENDED (flags plus a configured tolerance; never refuse) | S | no |
| M6-03 | medium | YES | BUILD AS SUGGESTED | S | no |
| M6-04 | medium | YES, wider than reported | BUILD AMENDED (also check that the series is the device's own RCT series) | S | no |
| M6-05 | low | YES | BUILD AS SUGGESTED | S | no |
| M6-06 | medium | PARTLY | BUILD AMENDED (audit only a named but unknown batch; no restock on a sale receipt) | S | no |
| M6-07 | medium | YES (gap, not reachable today) | DEFER | L | likely |
| M6-08 | high | YES | BUILD AMENDED (business-date filter, cursor, flagged filter, single-receipt read) | M | no |
| M6-09 | low | YES | BUILD AS SUGGESTED | S | no |
| M6-10 | low | YES | BUILD AS SUGGESTED | S | no |
| M8-02 | low | YES | BUILD AMENDED (clamp to central time, no envelope change) | S | no |
| M8-03 | medium | YES | DECIDE THEN BUILD (key on event id) | M | yes |
| M8-04 | medium, should be high | YES, worse than reported | BUILD AMENDED (line key `(receipt_id, line_no)`; device's shop) | M | yes |
| M8-05 | medium | YES (recorded gap) | DEFER | L | yes, when built |
| M8-06 | high | YES | BUILD AMENDED (key on the granted entities; no user id needed) | S | no |
| M8-07 (= RLS-08) | medium | YES | DECIDE THEN BUILD (location column, shared with RLS-08) | M | yes |
| M8-08 | medium | YES, wider than reported | DECIDE THEN BUILD (project the limit, including the one set at opening) | M | yes |
| M8-09 | medium | YES | BUILD AS SUGGESTED | S | no |
| M8-10 | low | YES | BUILD AS SUGGESTED | S | no |
| M8-11 | low | YES | DECIDE THEN BUILD | S | no |
| M8-12 | medium | YES, wider than reported | BUILD AMENDED (record FAILED in its own transaction) | S | no |
| M1A-01 | high | YES | DECIDE THEN BUILD | M | no |
| M1A-03 | high | YES | BUILD AMENDED (every last-holder count ignores TILL-only users) | S | no |
| M1A-04 | medium | PARTLY (latent: no permission takes limits yet) | DECIDE THEN BUILD (with M5-09) | S | no |
| M1A-05 | medium | YES | DECIDE THEN BUILD; the suggested ROLE-mode fix is WRONG | S | yes, only to drop a pair |
| M1A-06 | medium | YES | BUILD AMENDED (gate opening and activation) | S | no |
| M1A-07 | low | YES | BUILD AS SUGGESTED | S | no |

Counts: BUILD AS SUGGESTED 7, BUILD AMENDED 11, DECIDE THEN BUILD 7, DEFER 2, INVALID 0.

---

## Fix group A: till bundles, validate at the gateway, then apply and flag (M6-01, M6-02, M6-03, M6-04, M6-05)

**Order:** A1 (gateway shape) first. A2 (flags) can follow in the same pull request or the next. Decide D1 and D2 first.

### A1. M6-01: malformed bundle (BUILD AMENDED, M, no migration, kernel change)

1. **Valid? YES.** `PosIngestConsumer` (lines 59, 70, 139-158) and `RecordReceiptHandler:78-80` throw on every shape listed. `EventConsumerDispatcher.deliver` (lines 85-109) retries 3 times, then marks the event failed and dead-letters it. `pos.receipt_line.line_no CHECK (line_no >= 1)` and the primary keys in `V0001__pos.sql` refuse the inserts. `EventApplier` (lines 150-167) checks only the content hash over `document` and `lines`. The verifier missed something worse: **the split.** `m5.sales`, `m7.tenders` and `m8.shop-sales` each have their own queue for the same outbox event, so M5 can deduct the stock and M8 can count the sale while M6 has no receipt.
2. **Is the suggested fix correct? NEEDS CHANGES.** The suggested M6 quarantine table would leave the split in place: M5, M7 and M8 would still apply what M6 quarantined. Contract doc 32 already decides where a malformed fact goes: section 3.3 step 6 ("validate envelope and schema ... Store raw in sync_quarantine with the reason; advance the cursor; raise SYNC_ANOMALY ALERT"), the S4 row ("a payload that cannot be parsed is stored raw, the sequence advances, an alert is raised") and section 7 ("Malformed payload ... Quarantine, advance, ALERT"). The kernel already does this (`kernel.sync_quarantine`, `SYNC_EVENT_QUARANTINED` ALERT, `sync.anomaly.v1`, location-scoped since kernel V0083). Doc 32 distinguishes a quarantined malformed payload from a refusal for a business reason, so this does not break AGENTS.md's rule.
   **Amended fix:**
   - `kernel/internal/sync/EventApplier.java`: for `BUNDLE_TYPES`, check the generic doc 32 section 3.1 shape before the event reaches the outbox, and quarantine as `SCHEMA` when it fails:
     - `document.document_id` is a UUID.
     - `document.issued_at` is an instant.
     - Every money, quantity and price field present is a decimal string. `net_amount`, `tax_amount` and `gross_amount` count as present when not null.
     - Every UUID field present is a UUID.
     - `lines` is an array. Each line has an integer `line_no >= 1`, and line numbers are unique.
     - `tenders`, when present, each have an integer `seq >= 1`, unique, a non-blank `kind` and a decimal `amount`.
     
     Put it beside `BundleHash` as a `BundleShape` helper so every bundle type shares it.
   - Session events (`till_session.opened.v1` and `till_session.closed.v1`) are not bundles. Add `session_id` (UUID) and `opened_at`/`closed_at` (instant) to the same gateway check by type. The alternative is a small kernel SPI (`DevicePayloadCheck` in `kernel.api`, implemented in `m6pos`) so the kernel does not know M6's fields. Recommended: the SPI, since the kernel must not import M6.
   - `PosIngestConsumer`/`RecordReceiptHandler`: keep the two `ProblemException`s as programming guards only. After A1 they are unreachable from a till.
   - Tests: `QuarantineConformanceIntegrationTest` gets one case per shape. `TillSaleEndToEndIntegrationTest` asserts that a malformed receipt reaches none of M5, M6, M7 or M8.
3. **Best business solution?** A shop manager must never find stock deducted for a sale that is not in the receipt list. Quarantining before the outbox gives one outcome for every module. Alternatives rejected:
   - (a) Lenient parsing in M6 alone: the split remains.
   - (b) An M6 quarantine table: it duplicates the kernel and gives a second place to look.
   - (c) Renumbering bad lines: central would be inventing facts, which goes against AGENTS.md idea 3.
   
   **How the shop manager sees it:** today they see nothing; the ALERT reaches no screen. See decision D2.

   **Risk:** medium. This is a kernel change in the hot ingest path, and a check that is too strict quarantines good sales. The check must stay at the level of the shape the till already sends (`till/core/.../sale/Facts.kt`: no `line_id`, no `batch_id`, tax "0.00") and must not add business rules.

### A2. Receipt flags (M6-02, M6-03, M6-04, M6-05)

- **M6-02 (BUILD AMENDED, S, no migration).**
  1. **Valid? YES.** No arithmetic check anywhere (`RecordReceiptHandler:85-95`).
  2. **Fix: NEEDS CHANGES (minor).** Add the flags `TOTAL_MISMATCH` (gross is not net + tax, or gross is not the sum of `line_total`) and `TENDER_MISMATCH` (the sum of tender amounts is not gross). Use the existing REVIEW path (`RECEIPT_FLAGGED`). Do not refuse. Skip a comparison when a side is null; a null gross is itself flagged `TOTALS_MISSING`.
     - Tolerance: one ConfigRegistry key `pos.receipt.total_tolerance` (money, default 0.01 for the whole receipt, not per line). The till rounds each `line_total` to 2 dp and sums them into `gross` (`TillService.sellForCash`), so 0.01 has headroom.
     - Do not compare the sum of tenders with "greater than or equal": the till records the tender as the gross, not the cash handed over (`Facts.receiptPayload`).
  3. **Business:** flag, never reject, is right for a sale that happened. A basket-level discount would make sum(lines) differ from gross. The till has none today; the 26A line columns carry `discount_amount` per line. Revisit when offers arrive.
  - **Risk:** low.
- **M6-03 (BUILD AS SUGGESTED, S).**
  - Valid: `recorded()` returns before any comparison (lines 81-83, 213-216).
  - Fix: compare `content_hash`; keep the first; write an ALERT audit record `RECEIPT_REPLAY_DIFFERS` with both hashes. Do the same for a second session close with other amounts (`RecordSessionHandler:55-60`). This matches doc 32 section 7 ("quarantine the later copy; ALERT"). M5's `movementsCiting` keeps the first copy too, so all modules agree.
  - Optionally also store the raw differing payload in `kernel.sync_quarantine`. Not needed now.
  - **Risk:** low.
- **M6-04 (BUILD AMENDED, S).**
  - **Valid? YES, and wider.** `JdbcNumberingService.observeDeviceNumber` (line 189-201) raises any series the till names. Under `kernel.numbering_series.own_update` (kernel V0064:259-269) a device's OWN-at-shop scope may update the **entity's ENTITY series** (location NULL) and the shop's LOCATION series too. A till bundle naming the society's invoice or order series id can push that series' numbering forward.
  - **Amended fix** in `RecordReceiptHandler` before calling `observeDeviceNumber`:
    - Raise only when the series is `doc_type_code = 'RCT'`, `series_scope = 'TILL_POSITION'`, `location_id = scope.locationId()`, and the holder is this device or this till position.
    - Otherwise flag `SERIES_FOREIGN` and do not raise.
    - When the number is more than `pos.series.max_jump` (ConfigRegistry, default 10,000) above `next_number`, flag `NUMBER_JUMP`, raise by at most the gap, and write an ALERT.
    - The check belongs in the kernel (`observeDeviceNumber(seriesId, number, ScopeContext)` returning an outcome), so other bundle types that carry numbers (GRN captured at a shop) get it too.
  - **Risk:** low.
- **M6-05 (BUILD AS SUGGESTED, S).**
  - Add `NO_SESSION` for a null `session_id`, and `SESSION_CLOSED` when an applied close of that session has `closed_at < issued_at`.
  - The till always sells inside a session (`TillService`), so a null session id is a contract breach. Flag it, do not refuse. Sessions and receipts share the `m6.till` queue, so "an applied close" is in the device's own order.
  - **Risk:** low.

## Fix group B: sale deduction traces in M5 (M6-06)

**M6-06 (BUILD AMENDED, S, no migration).**
1. **Valid? PARTLY.** `ApplySaleHandler.resolveBatch` (lines 157-183) falls back to the shop's FEFO lot. That is the **normal** path when the till sends no batch: the shipping till never sends `batch_id` (`Facts.receiptLines`). It is a silent substitution only when the till named a batch M2 does not know. `qty <= 0` lines are skipped without a trace (lines 104-106).
2. **Fix: NEEDS CHANGES.**
   - Write the audit record `SALE_BATCH_SUBSTITUTED` (REVIEW) only when `line.batchId() != null` and the batch is unknown or of another SKU. The no-batch fallback stays silent, or a fallback that happens every sale would flood the exception queue.
   - Write `SALE_LINE_SKIPPED` (REVIEW) for `qty` null or `<= 0`.
   - Do not restock a negative line here. A return is a separate `receipt.refunded.v1` fact (M6-07).
3. **Business:** right.

**Risk:** low.

## Fix group C: receipt and session screens (M6-08, M6-09, M6-10)

**Order:** one pull request (backend, OpenAPI, generated client, web). Decide D3 first, or take its default.

- **M6-08 (BUILD AMENDED, M, no migration).**
  1. **Valid? YES:**
     - `PosQueriesImpl.receipts` runs one query for lines and one for tenders per receipt, with no limit (lines 29-87).
     - `sessions()` has no limit.
     - `ReceiptPage.tsx:27-53` loads the shop's whole list and both lists, then uses `find()`.
     - `PosQueries` is used only inside M6 (`PosController`, tests), so its signature may change.
  2. **Amended fix:**
     - `GET /v1/pos/receipts?location&businessDate&flagged&cursor&limit`: `businessDate` defaults to today in the business timezone. The cursor is the opaque `(issued_at, document_id)` of the last row. `limit` defaults to 50, with a ConfigRegistry maximum `pos.list.max_page` = 200. `flagged=true` returns only `cardinality(flags) > 0`.
     - Lines and tenders for the page come from two `document_id = any(?)` queries.
     - `GET /v1/pos/receipts/{documentId}` (RLS decides visibility; 404 when not found) and `GET /v1/pos/sessions/{sessionId}`.
     - Sessions are paged the same way, by `opened_at`/business date.
     - Index: `receipt_by_location (location_id, issued_at)` serves the cursor. For the business-date filter add nothing yet; `issued_at` within the day's bounds serves it.
     - Regenerate with `tools/gen-clients.sh`. `ReceiptPage` uses the single read.
  3. **Business:** a shop manager works by trading day, so the date filter matters more than the cursor. The flagged filter is how they find what central questioned (see D2).
  
  **Risk:** low to medium (an API shape change, but there is one web consumer).
- **M6-09 (BUILD AS SUGGESTED, S, web).** Map each flag to `pos.flag.<CODE>` with en/si/ta text, and give it a one-line explanation of what to do. An unknown code falls back to the code itself, so a new backend flag never breaks the page. Include the A2 flags.
- **M6-10 (BUILD AS SUGGESTED, S).** List from the union of `till_session` and `till_session_close` (full outer join on `session_id`), with the open fields null for an orphan close. Do it inside the paged query of M6-08. The location filter must apply to both sides.

## Fix group D: voids and refunds (M6-07), DEFER

**M6-07 (DEFER, L, a migration is likely).**
1. **Valid? YES as a gap**, and recorded: `m6pos/README.md` item 4. It is not reachable today: the till (`till/**/*.kt`) emits no `receipt.voided` or `receipt.refunded`.
2. **The suggested fix** is the right outline, but it belongs to the feature that makes the till void and refund:
   - M6 records the reversal linked to the original.
   - M5 restocks into the original batch or the FEFO lot, by the same `SALE_REVERSAL` movement type.
   - M8 writes a negative fact.
   - It needs 26A's `RefundCrossSessionHandler` decisions.
3. **Defer** with one guard now: M7's `ReceiptTenderConsumer` already consumes both types. When the till feature starts, M5, M6 and M8 must ship in the same release, or the account is credited while stock and sales are not reversed. Write that into the till's ticket.

## Fix group E: shop sales projection (M8-04)

**M8-04 (BUILD AMENDED, M, migration `m8reporting` next free number, currently V0007).**
1. **Valid? YES, and worse than reported.** The shipping till never sends `line_id`: it is not in `Facts.receiptLines` and not in the contract's line hash fields (`ContentHash.LINE`). So `ShopSaleProjection:70-73` skips **every line of every real till receipt**. `shop_sale_line_fact` is empty and "sales by item" shows nothing for real sales. Only bundles made by tests or the demo loader carry `line_id`. I would rate it **high**.
   - The location point holds too: `location` is taken from the till's `document.location_id` (lines 55-58) while M6 uses the device's shop.
2. **Amended fix:**
   - The line key is `(receipt_id, line_no)`: the migration adds `line_no integer` and makes `(receipt_id, line_no)` the primary key. Existing rows keep their `line_id`; make it nullable.
   - The location is `scope.locationId()` (the device's shop, as M6 does), with the till's value only as a fallback when the scope has none.
   - Skipped lines (no `sku_id` or `qty`) are counted into a `skipped_lines` column, or simply not counted, since the gateway check of A1 will have refused unreadable shapes.
   - A null `document_id` cannot occur after A1.
3. **Business:** a society judges a shop by what sold. This is the most visible defect in M8 for the demo path.

**Risk:** medium. A primary key change on a populated table works in a new migration, because lines with no `line_id` were never inserted, so no duplicates exist. Old receipts stay without lines until a rebuild (M8-05).

## Fix group F: dashboard cache (M8-06)

**M8-06 (BUILD AMENDED, S, no migration).**
1. **Valid? YES.** `ReportQueriesImpl.CacheKey` (lines 71-72, 193-200). Every M8 policy reads only `scope_class`, `scope_entity`, `scope_location` and `granted_entities()` (grep of `db/migration/m8reporting/*.sql`), and the exception queue and tiles depend on nothing else except `seesCost` and the language.
2. **Amended fix:** add `Set<UUID> granted` (`scope.grantedEntities()`, an immutable set with value equality) to `CacheKey`.
   - The user id is **not** needed: no M8 policy is per user. Adding it would only lower the hit rate.
   - A revoked or expired grant changes the next request's granted set and so its key. The old entry is never served to a scope without that grant.
   - Also key on `scope.deviceId() != null`, which `seesCost` already folds into `cost`.
3. **Business:** this matches 28A's "cached 60 s per (tile, scope)". A scope is the whole visibility (class, entity, location, grants), not the home entity. Not caching EXTERNAL at all was the rejected alternative: it costs nothing to key correctly.

**Risk:** low. Test: two EXTERNAL scopes of one home entity with different grants get different dashboards inside the cache window.

## Fix group G: trade projections by location (M8-07 with RLS-08: one fix)

**M8-07 / RLS-08 (DECIDE THEN BUILD, M, migration `m8reporting` next free number).**
1. **Valid? YES.** The `V0002` and `V0004` trade tables have no location column and `own_read` tests the entity only (V0002:53-63, 90-100). The shop templates in the demo seed grant `rpt.report.run` (`demo-users.demo.sql:157, 168, 175`).
2. **The suggested fixes:**
   - The code guard in `TileSources`/`ReportSources` would leave the tables open to any future read. It also hides from a shop the GRNs it received itself.
   - `scope_location() IS NULL` in the policies hides everything trading from a shop, including its own deliveries.
   
   Neither matches what is already decided.
3. **Business and design:** kernel V0061 (27 September, accepted on the architect's delegation; doc 18 section 3.7; M-05) decided that **a shop-scoped session reads the documents of its shop only**, and that entity-wide documents (location NULL: orders raised entity-wide, invoices, payments) are not a shop's. 28A section 3 says every reporting table carries `location_id` "where meaningful". The projections should follow the same rule, not a new one. Recommended fix (decision D4):
   - Add `location_id uuid NULL` to `trade_document_event`, `trade_line_fact`, `trade_settlement_fact`, `trade_document_link` and `exposure_warning_event`.
   - `own_read` / `own_write` gain `AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location())`, so a NULL location is visible only to entity-wide sessions.
   - `party_read` gains `AND kernel.scope_location() IS NULL`: a counterparty's rows are an entity-level matter, and the location on them is the other entity's.
   - `TradeProjection` fills `location_id` from the event where it names the owner's location (`GrnConfirmed`, `GrnCaptured`, `DiscrepancyRaised`, `ClaimRaised`, `DeliveryNoteIssued` and the transfer events carry one; grep `m4trading/api`). Otherwise it stays NULL. Invoices, payments, settlements and exposure stay NULL, so they are entity-only.
   - `TileSources` leaves a tile out when its value is empty for a location-scoped session (receivables, payables, exposure), so the shop dashboard does not show zeros as facts.
   - Backfill: optional. Old rows with NULL fail closed (hidden from shops, visible entity-wide). A migration UPDATE under FORCE RLS would see no rows anyway unless the migration lifts FORCE for its statement, so leave it to the rebuild (M8-05).

**Risk:** medium (policy change; the RLS matrix rows in `SchemaRulesIntegrationTest` and the M8 RLS tests need the new location cases).

## Fix group H: trade projection keys, freshness and rebuild (M8-02, M8-03, M8-05)

- **M8-03 (DECIDE THEN BUILD, M, migration).**
  1. Valid: the key `(document_id, event_kind, owner_entity_id)` with `ON CONFLICT DO NOTHING` (`TradeProjection:335`, V0002:42); a second dispute after a resolution is allowed by M4.
  2. Fix (decision D5):
     - Make `event_id` the primary key. The column exists and is filled. Keep `(document_id, event_kind, owner_entity_id)` as a plain index.
     - Inbox idempotency already stops a redelivery, so the old key bought nothing.
     - Then review every `TradeSql` and `ExceptionQueue` query that assumes one row per kind. Kinds that can repeat (`DISPUTED`, `DISPUTE_RESOLVED`, payment `SETTLED`/`REVERSED`) must take the latest by `occurred_at, event_id` or sum deliberately.
     - Settlement: key on `(event_id, invoice_id, line ordinal)` only if M4 can settle one invoice twice from one receipt. I could not settle that (see the last section).
  3. Rejected: a per-document sequence column. It needs one more counter, and the event id already orders well enough with `occurred_at`.
  
  **Risk:** medium. A missed query double-counts money on the dashboard. Rows lost before the fix return only with M8-05.
- **M8-02 (BUILD AMENDED, S, no migration).**
  - Valid (`ProjectionStateStore` advances by the device's `occurredAt`).
  - Amended fix: write `least(?, now())` into `last_event_at`, so a till clock ahead of central cannot freeze freshness. Keep the guard. Ordering by outbox sequence needs a kernel envelope change and buys little for a "data as of" label.
  - Doc 32 section 7 also wants reporting to use corrected times for clock drift; the clamp is the cheap half of that.
- **M8-05 (DEFER, L, a migration or maintenance role when built).**
  - Valid and recorded in `m8reporting/README.md`. A rebuild service (28A `sys.projection.rebuild`) is its own ticket, with a decision on the replay source (outbox rows are kept, insert-only) and a privileged truncate path.
  - Defer, but each of E, G and H must state in its pull request what old rows look like until a rebuild. None of them corrupts data; they leave old rows incomplete.

## Fix group I: credit limit, gated where it is set, projected where it is read (M1A-06, M8-08)

**Order:** M1A-06 first (it decides which event carries the opening limit), then M8-08.

- **M1A-06 (BUILD AMENDED, S, no migration).**
  1. Valid:
     - `OpenTradingRelationshipHandler` (`@CommandHandler(permission = "prt.relationship.open")`, line 28) stores `command.creditLimit()` (line 152) with no check.
     - `ActivateRelationshipHandler` checks nothing either.
     - `AmendRelationshipTermsHandler.requireCreditLimitPermission` (lines 248-260) is `permissions.ifPresent(...)` with the stale K-03b comment.
  2. Amended fix:
     - Make the `PermissionResolver` a required constructor dependency (fail at start, not open).
     - In Open, when `creditLimit` is non-null: require `bil.creditlimit.change` and fresh MFA. Reuse Amend's `requireFreshMfa`, because `@CommandHandler.requiresMfa` is static and the check is conditional.
     - In Activate, when the draft carries a non-null limit: the same two checks, because activation is when the limit takes effect and the drafter may be another person.
     - **Activate publishes `credit_limit.changed.v1`** (from null to the limit) in the same transaction, so the opening limit becomes an event (needed by M8-08).
  3. Business: doc 21 (FR-SEC-030; section 6.4 "Raising a credit limit": MFA and a reason) gates changes to the limit. Setting a limit at opening is the first change. Rejected: gating only Activate, which would let a draft carry an unreviewed figure into the audit trail as if approved.
- **M8-08 (DECIDE THEN BUILD, M, migration `m8reporting`).**
  1. **Valid? YES, and wider.** `TradeSql.EXPOSURE` (lines 48-85) takes the limit from the latest warning and the threshold as `min(...) over (partition by relationship_id)`. Also: `RelationshipActivated` carries no `creditLimit` (`m1party/api`). So even projecting `credit_limit.changed.v1`, as suggested, misses every relationship whose limit was set at opening and never amended (most of them).
  2. Fix (decision D6):
     - Add a projection table `reporting.credit_limit_fact (relationship_id, seller, buyer, credit_limit, effective_from, event_id)` with the four-class policies, fed by `credit_limit.changed.v1`, including the activation event from M1A-06.
     - The exposure view lists every relationship with a current limit > 0 (not only the ones that once warned).
     - It computes `exposure / current limit` and picks the **highest** threshold of `trading.exposure_warn_thresholds` (ConfigRegistry, the key M4 uses) that the current ratio reaches.
     - Existing relationships: the migration cannot read M1. Run a one-off M1 job that publishes `credit_limit.changed.v1` for each ACTIVE relationship with a limit (audited), or let the rebuild handle it.
  3. Alternatives:
     - (a) Read the current limit through `m1party::query.RelationshipQueries`: no migration, and allowed by `package-info`, but 28A section 1 excludes "any figure that is not derived from events" from M8. Rejected.
     - (b) Keep the warning-driven view but use the latest threshold: it still misses never-warned buyers. Rejected.

## Fix group J: reports, exports and print runs (M8-09, M8-10, M8-11, M8-12)

- **M8-09 (BUILD AS SUGGESTED, S).**
  - Add ConfigRegistry keys `reporting.max_period_days` (366) and `reporting.max_rows` (10,000), and refuse with `m8.report.period_too_long` / `m8.report.too_many_rows` (en/si/ta).
  - Apply both in `checkPeriod` and in `ReportSources`, before building names. The row cap must be applied in SQL (`limit max+1`), not after loading.
  - 28A's export worker with a streaming writer and a cap is the long-term path for larger pulls.
- **M8-10 (BUILD AS SUGGESTED, S).**
  - `ReportCsv.text` (line 44-47): also prefix when the first character is `\t`, `\r` or `\n`. Keep the rule for TEXT columns only.
  - MONEY columns can start with "-" legitimately and are numbers, so do not prefix them.
- **M8-11 (DECIDE THEN BUILD, S).**
  - Valid: `exportReportCsv` writes no audit record.
  - Decision D7: audit `REPORT_EXPORTED` (INFO; report id, parameters, row count, no row content) for the CSV export only. The audit runs in a read-write transaction through the audit facade, as `RequestReportRun` already does for prints.
  - Do not audit `/data` screen reads: they are the dashboard's daily traffic.
  - No rate limit in v1; the row cap of M8-09 bounds each pull.
- **M8-12 (BUILD AMENDED, S).**
  1. **Valid? YES, and wider.** `ReportRunWorker.onRequested` runs inside the dispatcher's transaction (`EventConsumerDispatcher:77`). `ReportQueriesImpl` is `@Transactional(readOnly = true)` with REQUIRED propagation. So a `RuntimeException` (including a `ProblemException`, which `extends RuntimeException`) thrown from `queries.report(...)` marks the outer transaction rollback-only. A PostgreSQL error also aborts it. Catching `RuntimeException`, as suggested, would still end with `complete.handle` failing at commit and the run dead-lettered. The existing FAILED test passes only because its failure comes from the renderer stub, outside a transactional proxy.
  2. Amended fix:
     - Run the read and render in their own transaction: a `TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`, read-only, around `queries.report` plus the render.
     - Catch `RuntimeException` there and map it to `m8.run.render_failed` (or the `ProblemException`'s id).
     - Then call `complete.handle` in the outer transaction.
     - Add a test where the report read throws (an unknown report id through the dispatcher path).
  3. Business: a user can simply print again, so FAILED beats an automatic retry.

## Fix group K: who may reset or change whose credentials, and the last user manager (M1A-01, M1A-03)

**Order:** M1A-03 can go first (no decision needed). M1A-01 after D8.

- **M1A-03 (BUILD AMENDED, S, no migration).**
  1. Valid:
     - `UpdateUserHandler` (lines 64-87) takes no `EntityLock` and checks no last holder.
     - A move to TILL disables the login.
     - `UserFacts.USER_MANAGERS` (lines 23-34) counts any ACTIVE user regardless of `user_kind`.
  2. Amended fix:
     - In UpdateUser, when `hadLogin && !user.hasLogin()`: take `lock.lock(entityId)` and refuse `m1.user.last_user_manager` if `holdsUserManage && !anotherUserManagerExists`.
     - **And** change every count of "another user manager" to count only users whose kind has a login (`BACK_OFFICE`, `BOTH`). Today that is `UserFacts.USER_MANAGERS` and the security-definer count that RevokeRole and AmendRole use (`security.role_assignment_count` / `userManagerHoldings`, m1security V0010). Changing the definer function needs a new m1security migration (next free number, currently V0018). The two counts must agree, or the role handlers still pass a TILL-only manager as "another".
     
     Correction to the summary row: this is "no migration" only if the role-side count already joins `app_user.user_kind`. If it does not, the fix needs one m1security migration.
  3. Business: an MPCS that loses its last person who can manage users cannot recover in-app (ADR-18 per the finding; not re-checked).
  
  **Risk:** low.
- **M1A-01 (DECIDE THEN BUILD, M, no migration).**
  1. **Valid? YES.** `ResetCredentialHandler.handle` (lines 76-105) guards scope, deactivation and applicability only. UpdateUser and DeactivateUser have the same gap: a user manager can disable the role manager's login by changing their kind to TILL, or deactivate them.
  2. **The suggested fix:** applying the strict AssignRole within-grantor rule ("refuse when the target holds any permission the caller does not") is safe but too strict for a society. The seeded Entity Administrator template (`role-templates.yaml:22-56`) lacks finance codes such as `bil.payment.record`, so the society's own administrator could not reset the cashier's password.
  3. Recommended (decision D8):
     - Refuse ResetCredential, the UpdateUser kind change that closes the back office, and DeactivateUser when the target holds, at the entity, any permission whose catalogue entry has `requires_mfa = true` and that the caller does not hold (`m1.user.target_outranks_caller`). Those are the sensitive codes: user and role management, credit limit, approvals.
     - Also refuse a self-reset of SECOND_FACTOR (`m1.user.credential_self`). A person who lost their device is reset by another holder; the last-holder rule guarantees at least one exists, but a society should have two (see the last section).
     - Keep returning the temporary password to the caller when M9 has no delivery rule; under this rule the caller already outranks or equals the target.
     
     Alternatives rejected:
     - (a) The strict within-grantor rule: it locks routine helpdesk work behind the administrator.
     - (b) Protect only `gov.*` holders: it leaves credit-limit and approval holders exposed.
     - (c) Notification-only delivery: shops without e-mail or SMS would have no path.
     
     The target's holdings come from `SecurityRecords.holdingsAt(entityId, [target], null)` (all of the target's assignments at the entity, any location). The catalogue's MFA flag comes from `records.catalogue(codes)`.
  
  **Risk:** medium. It changes who can do helpdesk work. Tests per kind (PASSWORD, SECOND_FACTOR, PIN) and per path (reset, kind change, deactivate).

## Fix group L: limits on roles (M1A-04), with M5-09

**M1A-04 (DECIDE THEN BUILD, S, no migration).**
1. **Valid? PARTLY.** The comparison is codes only (`RoleGuards.withinGrantor`, lines 138-145), but no permission in the catalogue has a `limits_schema` yet, so today every limit is refused (`m1.role.limits_not_accepted`; `m1party/README.md` line 117). The defect becomes live the day limits are seeded, which M5-09 (wave2-m5-m3.md) requires for the write-off and adjustment approval bands.
2. **Fix:** the suggestion is right in outline.
3. Recommended (decision D9): **a granted limit may never exceed the grantor's own.**
   - For each numeric property of the schema (`maximum`-style fields such as `max_value`), the requested value must be at or below the grantor's effective value for that code at the entity.
   - The grantor's effective value is the highest over their holdings; a holding with no limits counts as unlimited.
   - A grantor with no limit on a field may grant any value.
   - Apply it in `RoleGuards.permissions` (CreateRole, AmendRole) and in `AssignRole.withinGrantor`, with message `m1.role.limit_exceeds_grantor`.
   - **Build it in the same pull request that seeds the first `limits_schema`** (M5-09), not before. Built alone it has nothing to test against.

## Fix group M: segregation pairs in INSTANCE mode (M1A-05)

**M1A-05 (DECIDE THEN BUILD, S; a migration only to remove one pair).**
1. **Valid? YES.** Both pairs are INSTANCE (`seed/m1party/sod-pairs.yaml:6-13`), and no M1 handler calls `Sod.assertDistinct`.
2. **The suggested fix is WRONG.** Seeding the pairs in ROLE mode:
   - The Entity Administrator template holds **both** `gov.user.manage` and `gov.role.manage` (`role-templates.yaml:29-31`). Under ROLE mode, `RoleGuards.permissions` would refuse to author, clone or amend that template, and every entity's administrator would hold a forbidden pair.
   - A small MPCS has one administrator.
   - It contradicts the design: doc 21 section 3 (lines 189-197: "user.manage/audit.review(self) at INSTANCE; role.manage guardrails are code") and 21A's seed (lines 366-372). The design's four defaults do not include a `gov.role.manage`/`gov.user.manage` pair at all; it was added in M1-08.
   - Flipping a mode by migration would also skip the "no ROLE raise while someone holds both" guard.
3. Recommended (decision D10):
   - (a) Keep `gov.audit.review`/`gov.user.manage` INSTANCE. Enforce it where its instance exists, at the exception acknowledgement of 28A (`ExceptionItemConsumer`, `AUDIT_REVIEWED`): a reviewer may not acknowledge an exception whose `actor_user_id` is themselves. Build it with that ticket, which is not built yet. Until then nothing reviews audit trails, so nothing is bypassed.
   - (b) Remove the `gov.role.manage`/`gov.user.manage` pair with a new m1security migration (delete by id `01921319-dfc4-7264-b580-c11df5ed4c53`, as V0014 did for the retired pair) and from the seed. Its protection is the code guardrails (within-grantor, last holder, and D8's reset rule), which is what doc 21 says.
   - Rejected: keeping a pair that nothing enforces, because it suggests a control that does not exist.

## Fix group N: web route guard for M8 (M1A-07)

**M1A-07 (BUILD AS SUGGESTED, S, web).** Guard the dashboard, report and exception routes on `rpt.report.run`; guard the run history and print controls on `rpt.export.run`. It affects only custom roles.

---

## Decisions to record

Each is written so it can be recorded as "accepted on the architect's delegation". None is accepted by this review.

- **D1. Malformed till payloads are quarantined at the gateway, not in modules** (M6-01).
  - Decision: a till event whose shape breaks doc 32 section 3.1 (bundle header ids and time, line numbers ≥ 1 and unique, tender sequence, decimal and UUID fields; for sessions, id and time) is quarantined by the kernel sync gateway as `SCHEMA`, before the outbox. Modules then never see it, and all modules agree.
  - Module consumers apply and flag business oddities only.
  - Rejected: lenient parsing in each module (modules disagree), and an M6 quarantine table (a second store).
  - Why: doc 32 S4, section 3.3 step 6 and section 7 already say so; the kernel table, ALERT and event exist.
- **D2. How a shop manager sees what central questioned** (M6-01, M6-02).
  - Decision: flagged receipts are found on the receipts screen with a "flagged" filter and translated flag texts (M6-08, M6-09).
  - Quarantined uploads and flagged receipts become exception items for the shop's location (28A `exception_item` from REVIEW/ALERT audit records, plus `sync.anomaly.v1`), seen by the shop in charge and the MPCS administrator. The till's resend through doc 32 section 8 is the remedy for a quarantined upload.
  - Quarantine rows are kept for the audit retention period.
  - Rejected: an e-mail per flag (noise), and a separate quarantine screen in v1.
- **D3. Receipt list paging** (M6-08).
  - Decision: lists are per business day, newest first, 50 per page, a configured maximum of 200 (`pos.list.max_page`), with a cursor and a "flagged only" filter. One receipt and one session have their own reads.
  - Rejected: offset paging (unstable while sales arrive) and a fixed page size in code.
- **D4. Trading projections follow the shop-reads-its-shop rule of kernel V0061** (M8-07, RLS-08).
  - Decision: each trade projection row carries the owner's `location_id` when the source document is at a location, else NULL.
  - A location-scoped session reads only rows of its location; NULL rows are entity-level.
  - Counterparty rows (`party_read`) are read entity-wide only.
  - Tiles with no meaning for a shop are left out for a shop session.
  - Rejected: a code guard (leaves the tables open) and hiding all trading from shops (hides their own GRNs).
- **D5. Trade projection rows are keyed by event id** (M8-03).
  - Decision: `trade_document_event` is keyed by `event_id`. Queries over kinds that can repeat take the latest or sum explicitly.
  - Rejected: a per-document sequence.
- **D6. Exposure uses the current limit, as an event** (M8-08, M1A-06).
  - Decision: activation of a relationship publishes `credit_limit.changed.v1` with the opening limit.
  - M8 projects every `credit_limit.changed.v1` and computes exposure for every relationship with a limit, against the current limit, reporting the highest configured threshold (`trading.exposure_warn_thresholds`) currently reached.
  - Existing relationships are seeded by a one-off audited publication.
  - Rejected: reading M1 at query time (28A: figures come from events), and keeping the warning-driven view.
- **D7. Report exports are audited, screen reads are not** (M8-11).
  - Decision: each CSV export writes `REPORT_EXPORTED` (report id, parameters, row count). No rate limit in v1 beyond the row cap.
- **D8. Who may reset or disable whose credentials** (M1A-01).
  - Decision: a user manager may reset a credential of, change the kind of, or deactivate a user only if the caller holds every MFA-flagged permission the target holds at the entity. Nobody resets their own second factor through this command.
  - The temporary password may still be returned to the caller when no delivery rule exists.
  - Rejected: the strict within-grantor rule (blocks routine helpdesk work), protecting only `gov.*` holders (leaves approvers exposed), and notification-only delivery (shops without e-mail or SMS).
- **D9. A granted limit never exceeds the grantor's** (M1A-04).
  - Decision: for each numeric limit field, the granted value is at or below the grantor's effective value (the highest over their holdings; no limit counts as unlimited).
  - Enforced at role authoring and at assignment, shipped with the first `limits_schema` (M5-09).
- **D10. Segregation pairs for administration** (M1A-05).
  - Decision: `gov.audit.review`/`gov.user.manage` stays INSTANCE and is enforced at exception acknowledgement (a reviewer does not acknowledge their own actions) when that is built.
  - The `gov.role.manage`/`gov.user.manage` pair, which is not in doc 21 or 21A, is removed. Its protection is the code guardrails.
  - Rejected: ROLE mode (it breaks the Entity Administrator template and one-administrator societies).

## Findings I could not settle

- **Settlement key in M8-03:** can one M4 receipt settle one invoice in two settlement lines (so that `(source_document_id, invoice_id, kind)` drops one)? Reading M4's payment allocation handler, or a test recording a split allocation, would settle it.
- **The M1A-03 role-side count:** does `security.role_assignment_count` / `userManagerHoldings` (m1security V0010 and later) join `app_user.user_kind`? This decides whether M1A-03 needs an m1security migration. Read the latest definition of the function.
- **M8-12 today:** does a `ProblemException` from `queries.report` already dead-letter the run? I reasoned from REQUIRED propagation and the dispatcher's `TransactionTemplate`. A test that requests a run whose read fails, delivered through `EventConsumerDispatcher`, settles it.
- **The M6-01 gateway check versus M7:** `m7.tenders` reads `receipt.refunded`/`voided` bundles. Confirm the shape rules of A1 hold for those types too before applying them to all `BUNDLE_TYPES`, or apply them per type.
- **D8 recovery for a one-administrator society:** if its only user manager loses their phone, who resets their second factor? ADR-18 says the Federation cannot repair in-app. Either a second holder is required at activation, or a provider-side helpdesk procedure is written. This is an architect question, raised and not answered here.
- **M8-04 demo data:** does the demo loader (not reviewed) send `line_id`? If it does, existing demo rows stay keyed by `line_id` and new till rows by `line_no`. Harmless, but the by-item report must not double-count a receipt replayed by both.
