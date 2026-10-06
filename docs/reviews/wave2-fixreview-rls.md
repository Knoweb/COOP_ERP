# Wave 2 fix review: row-level security (RLS-01 to RLS-17)

Second opinion before any fix is built. Reviewed 6 October 2026 against `main` 91e550d8 (branch `review/wave2-findings`), reading only: the migrations, the RLS template, the two RLS tests, every Java caller named below, doc 18 section 3.7, doc 27 sections 5.2, 9.3 and 9.5, 23A section 3, 22A section 6, 24A section 3, 25A section 10, the module READMEs and `docs/DECISIONS_PENDING.md`. Nothing was run (Docker down). Findings file: `docs/reviews/wave2-rls.md`. RLS-01 shares its root cause with M7CR-01 (`docs/reviews/wave2-m7-credit.md`); the hash design is not redone here.

Two facts that bear on every migration below:

- **The demo server keeps its database across releases under strict Flyway** (`docs/DEMO_SETUP.md`, "Migrations": `MIGRATION_OUT_OF_ORDER` stays `false` on the server). Flyway refuses a resolved migration whose version is below one already applied (`kernel/README.md`, measured 21 September). So the kernel's lane ranges are finished: every new kernel migration is numbered above `V0083` (`V0084`, `V0085` ...), whatever lane it belongs to. Module folders have their own history and simply take the next number: m1security `V0018`, m2catalogue `V0008`, m3pricing `V0006`, m4trading `V0009`, m5inventory `V0007`, m7customers `V0003`, m8reporting `V0007`, m9integration `V0006`. A merged migration is never edited; every fix below is `CREATE OR REPLACE`, `ALTER POLICY`, `DROP POLICY` + `CREATE POLICY`, `ALTER TABLE ... ADD COLUMN` or `ALTER FUNCTION ... SET` in a new file.
- `backend/app/src/main/resources/db/migration/kernel/README.md` carries unresolved merge-conflict markers (`<<<<<<< HEAD` at lines 10-18). Flyway ignores it, but the next kernel migration PR should resolve them (docs-only; not an RLS finding).

## Summary

| Id | Severity | Valid? | Fix verdict | Size | Migration |
|---|---|---|---|---|---|
| RLS-01 | high | YES | BUILD AMENDED (RLS part rides on RLS-02; hash per M7CR-01) | M | yes (m7customers V0003, shared with RLS-02/03) |
| RLS-02 | low | YES (a design gap, not a defect against the documents) | DECIDE THEN BUILD | S | yes (m7customers V0003) |
| RLS-03 | low | YES | BUILD AMENDED | S | yes (m7customers V0003) |
| RLS-04 | medium | YES (mrp_policy part NO) | BUILD AMENDED | S | yes (m3pricing V0006) |
| RLS-05 | low | YES | BUILD AMENDED | S | yes (m3pricing V0006) |
| RLS-06 | low | YES | BUILD AMENDED (one fix with M9-06) | S | yes (m9integration V0006 + m1party V0015) |
| RLS-07 | medium | YES | DECIDE THEN BUILD | M | yes (m2catalogue V0008, m5inventory V0007) |
| RLS-08 | medium | YES | DECIDE THEN BUILD (one fix with M8-07) | M | yes (m8reporting V0007) |
| RLS-09 | medium | YES; suggested fix WRONG as written (breaks nine handlers, not two) | BUILD AMENDED | M | yes (kernel V0084, m4trading V0009) |
| RLS-10 | low | YES | DECIDE THEN BUILD | M | yes (m4trading, after the decision) |
| RLS-11 | low | YES | BUILD AS SUGGESTED | S | yes (m4trading V0009) |
| RLS-12 | low | YES | BUILD AMENDED | S | yes (m5inventory V0007) |
| RLS-13 | low | YES | BUILD AS SUGGESTED | S | yes (one per module) |
| RLS-14 | low | YES | BUILD AMENDED | S | yes (kernel V0085) |
| RLS-15 | low | PARTLY (reachable only by SQL; the verifier's second option would break the demo) | DEFER, with the fix named | S | yes, when built (m5inventory) |
| RLS-16 | low | YES | BUILD AS SUGGESTED | S | yes (m1security V0018) |
| RLS-17 | low | YES | BUILD AS SUGGESTED (+ two more gaps) | M | no |

Counts: BUILD AS SUGGESTED 3; BUILD AMENDED 8; DECIDE THEN BUILD 4; DEFER 1; INVALID 0. Suggested fixes rated WRONG: RLS-09 (as written), RLS-15 (second option), RLS-04 (mrp_policy clause).

## Fix group A: member identity and who may read it (RLS-01, RLS-02)

**Decide first:** D-1 below (does FEDERATION_VIEW or an external grantee read member identity).

### RLS-01 (high) BUILD AMENDED

1. **Valid: YES.** `nic_hash` is `sha256("nic:" + NIC)` beside `nic_last4` (M7CR-01 reproduced it). V0001:164-193 gives the row to own_read, `account_holder_read`, `fed_view` and `ext_view`. No column masking exists in RLS, so every reader of the row reads the hash.
2. **Suggested fix:** NEEDS CHANGES only in the split of work. The hash (HMAC with a pepper outside the database; re-key existing rows as `HMAC(pepper, old_sha256)` from a Java step) is M7CR-01's and is not repeated. The RLS side has three pieces, which the finding lists but does not separate:
   - (a) Take the row out of FEDERATION_VIEW and EXTERNAL reach. With D-1 as recommended, this is the RLS-02 migration (drop `fed_view`/`ext_view` on `customers.customer`, `customer_phone`, `customer_consent`). If D-1 goes the other way, build instead a `customers.customer_identity_secret (customer_id PK, nic_hash, nic_last4, owner_entity_id)` with own_* only and move the two columns there; a security_invoker masking view does not help, because it reads under the same policies.
   - (b) Any new NIC lookup across societies (M7CR-15) must be a SECURITY DEFINER function built on the pattern of group B (class test, caller from the scope, boolean or own ids only). Do not add `nic_hash` to `phone_holders`.
   - (c) Leave `nic_hash` out of the ACCESS export (M7CR-11; `PrivacyExporter` does `select *`).
3. **Best solution:** doc 27 section 3.1/9.3 asks for a salted hash; doc 10 L-06 ("NIC as hash plus last four") stands. A keyed hash plus (a) is the whole fix; nothing else is needed on the RLS side. Alternative rejected: a per-row salt (breaks the equality guard `nic_held`, as M7CR-01 says).

Size M (Java re-key step + migration). Risk: the re-key must run once, with the pepper, before the new code compares hashes; a half-run leaves two hash forms in one column. Record the form in a column (`nic_hash_kind`) or re-key in the same deploy.

### RLS-02 (low) DECIDE THEN BUILD

1. **Valid: YES**, as a design decision. The policies follow doc 18 section 3.7 (FEDERATION_VIEW reads "Everything") and doc 27 section 9.5 ("Federation view-only"). Against them: doc 27 section 9.3 ("every read of a customer record outside the sales path is audited"), which a FEDERATION_VIEW SQL read cannot meet; doc 10 L-01/L-02 (joint controllers, data-governance agreement per entity, not yet signed); and the fact that no Federation code reads `customers.*` at all (`grep customers\. m8reporting` finds nothing; no M7 class uses FEDERATION_VIEW). The exposure after RLS-01 is names, phones and consents of every member of every society.
2. **Suggested fix: CORRECT** once decided. New m7customers `V0003`: `DROP POLICY fed_view`/`ext_view` on `customer`, `customer_phone`, `customer_consent`; keep them on `customer_account`, `account_posting`, `allocation`, `allocation_reversal`, `account_history`, `account_adjustment`, `doc_customer_payment` (money, no name). Add `RlsMatrixIntegrationTest.EXCEPTIONS` rows for the three tables with `onlyTheOwnerReads` (that predicate already exists: FEDERATION_VIEW and EXTERNAL hidden). `data_subject_request` carries free text (`notes`, `outcome`, M7CR-10): drop `ext_view` there too, keep `fed_view` until M7CR-10 settles what the text may hold.
3. **Best solution and the decision (D-1).** Recommend: *the Federation and external grantees read a society's credit book (accounts, balances, postings) but never member identity (name, phone, NIC hash, consents); identity reaches them only through the society, or through an audited, named export.* Reasons: a national cooperative federation holding the phone numbers of several million members readable by any view-all role is a data-minimisation problem under the PDPA (No. 9 of 2022) that L-01/L-02 have not yet settled; M8 needs aggregates and has them from the account tables; a regulator auditing an MPCS checks balances against accounts, not people. Alternatives rejected: (i) keep the template (doc 18 "Everything") and audit at the API only: the SQL read stays unaudited, against doc 27 section 9.3; (ii) a definer "masking" view giving the Federation `customer_id, owner, status, language` without name or phone: no consumer needs it in v1, and it can be added when one does. Documents: a CR against doc 18 section 3.7 (a carve-out for personal data of natural persons: FEDERATION_VIEW and EXTERNAL_TIMEBOXED read it through a module's audited query, never by policy) and doc 27 sections 5.2 and 9.5 (`LookupCustomer` loses "Federation view"). M7CR-12 was dropped for following the documents; this decision changes the documents, which is the right order.

Size S. Risk: low; nothing in the code reads those tables in those classes. One check at build time: `CustomerSnapshotContributor` and the privacy export run in OWN scope (they do).

## Fix group B: SECURITY DEFINER lookups that know who is asking (RLS-03, RLS-06, RLS-12, RLS-16) and definer hygiene (RLS-13, RLS-14)

**Decide first:** nothing; D-2 records the pattern so the next function follows it.

The pattern (recommend recording it in `RLS_POLICY_TEMPLATE.md` as a fifth case, "a function that answers across tenants"): (1) the function tests `kernel.scope_class()` first and returns nothing for every class that has no business asking (today `= 'OWN'`; a job's class is named explicitly when a job calls it); (2) the caller is `kernel.scope_entity()`, never a parameter; parameters name the thing asked about, never the entity answered for; (3) the answer is the smallest fact the guard needs: a boolean, a count, or ids the caller already owns; another tenant's ids never leave the function; (4) `SET search_path = pg_catalog, <schema>, pg_temp`; (5) `REVOKE ALL FROM PUBLIC; GRANT EXECUTE TO app_rw` stays, so the matrix of who may call is the class test inside, not the grant. This is what makes a counterparty recognisable without an oracle: the function can still say "yes, someone holds this phone" (reuse detection needs exactly that, 27A section 6.1), but only to an OWN session, and it no longer says who or where.

### RLS-03 (low) BUILD AMENDED

1. **Valid: YES.** `phone_holders` (V0001:325-338) has no class test and returns `customer_id, owner_entity_id` of every society's holder; `accounts_with_balance` (V0002:133-144) the same shape, a count.
2. **Suggested fix: NEEDS CHANGES.** The finding's return shape `(held_elsewhere boolean, own_customer_id uuid)` loses what `ReuseDetector.check` needs: one row per holder with `valid_to`, because NEEDS_CONFIRMATION depends on when each previous holder let the number go (within `customers.phone_reuse_window_months`). Corrected, m7customers `V0003`:
   ```sql
   CREATE OR REPLACE FUNCTION customers.phone_holders(p_phone text)
   RETURNS TABLE (own_customer_id uuid, held_by_caller boolean, valid_to timestamptz) ... SECURITY DEFINER
   SET search_path = pg_catalog, customers, pg_temp AS $$
       SELECT CASE WHEN ph.owner_entity_id = kernel.scope_entity() THEN ph.customer_id END,
              ph.owner_entity_id = kernel.scope_entity(), ph.valid_to
         FROM customers.customer_phone ph
        WHERE kernel.scope_class() = 'OWN' AND ph.phone = p_phone AND ph.is_primary
   $$;
   ```
   `ReuseDetector`: CONFLICT when a row has `valid_to IS NULL` and (`own_customer_id` is null or differs from `forCustomer`): `phone_held` with the id when `held_by_caller`, else `phone_held_elsewhere` (the same two refusals as today); NEEDS_CONFIRMATION counts rows with `valid_to` inside the window, and the audit records own ids only plus a count of others (today it records other societies' customer ids, which the finding did not note). `accounts_with_balance(p_customer)`: add `kernel.scope_class() = 'OWN'` and that the caller registered the customer (`EXISTS (SELECT 1 FROM customers.customer c WHERE c.customer_id = p_customer AND c.owner_entity_id = kernel.scope_entity())`, which as definer needs a `customer_directory ... TO app_seed USING (true)` policy on `customers.customer`, as `phone_directory` is on `customer_phone`); return the count, which the erasure guard needs (M7CR-09 will lock the accounts in Java; the count is unchanged).
3. **Best solution:** the amended function. Alternative rejected: moving reuse detection into Java with a FEDERATION_VIEW system scope (`SystemScope.federationView()`), which would read every phone row into the application; the definer function is the narrower door. The question M7CR-13 raises (how society B finds A's customer to open an account) is a different, audited door and must not be answered by widening this one.

Size S. Risk: the two i18n refusals and `CustomerHandlersIntegrationTest` are unchanged; the audit `after` map changes shape (own ids plus a count), which that test asserts.

### RLS-06 (low) BUILD AMENDED, one fix with M9-06

1. **Valid: YES.** `notification_recipients(p_entity, p_role)` (m9 V0003:24-40) answers any entity's ACTIVE addresses to any app_rw session; the only caller `ContactAudience.resolve` binds the event's owner or counterparty, and `NotificationService` runs it in `SystemScope.own(ownerEntityId, null)` (NotificationService.java:190), an OWN entity-wide scope.
2. **Suggested fix: NEEDS CHANGES** in where the relationship test lives. M9 cannot create a policy on `party.entity_relationship` for its definer function to read it (R4, `tools/check-schema-ownership.mjs`), and reading another module's table from a definer body is the kind of coupling M9-06's verifier flagged. Corrected: M1 publishes the fact the way it already publishes `party.trading_standing(entity)` (m1party V0007): a new m1party `V0015` function `party.caller_trades_with(p_entity uuid) RETURNS boolean`, SECURITY DEFINER, `kernel.scope_class() = 'OWN' AND EXISTS (ACTIVE relationship where (seller, buyer) = (scope_entity, p_entity) or the reverse)`; it answers only about the caller's own relationships, so it is no oracle (the directory `party_names_read` already shows each side its counterparties). Then m9integration `V0006` replaces `notification_recipients` with `WHERE kernel.scope_class() = 'OWN' AND (p_entity = kernel.scope_entity() OR party.caller_trades_with(p_entity))`, `search_path` with `pg_temp`. `fed_view` on `notification_contact` stays or goes with M9-06's decision (D-1's reasoning applies: an address is personal data, drop it; nothing in FEDERATION_VIEW reads it).
3. **Best solution:** the above. Alternatives rejected: the event-id route (derive the parties from the outbox row) needs the function to read `kernel.event_outbox` as definer, a kernel policy for app_seed, and a change to the `NotificationAudience` interface; resolving in a system scope with `p_entity = scope_entity()` only would make the kernel's dispatcher open two scopes per notification.

Size S (two migrations, no Java change). Risk: a relationship that is SUSPENDED when an invoice is dispatched would reach nobody at the counterparty; decide whether `caller_trades_with` admits SUSPENDED (recommend yes for notifications: a suspended buyer still owes and must be told; see RLS-05 for the opposite choice on prices).

### RLS-12 (low) BUILD AMENDED

1. **Valid: YES.** `inventory.entity_holds_lot_of(p_batch_id, p_entity_id)` (m5 V0002:25-33) takes the entity from the caller and is granted to app_rw with no class test.
2. **Suggested fix: NEEDS CHANGES** in one detail. New m5inventory `V0007`: `CREATE FUNCTION inventory.caller_holds_lot_of(p_batch_id uuid) RETURNS boolean` = `kernel.scope_class() = 'OWN' AND EXISTS (... owner_entity_id = kernel.scope_entity())`, granted to app_rw; `REVOKE EXECUTE ON inventory.entity_holds_lot_of(uuid, uuid) FROM app_rw` (keep the function: group D's trigger, which runs as the migrator, may call it; nothing else may). `sku_has_lot(p_sku_id)` keeps its shape (a SKU-wide yes/no the Federation needs for a SHARED SKU) but gains `kernel.scope_class() = 'OWN'`. Java: `InventoryLotQuery.holdsLotOf(UUID batchId, UUID entityId)` is M2's published api; change it to `holdsLotOf(UUID batchId, ScopeContext scope)` or keep the signature and have `LotQuestionsForCatalogue` ignore `entityId` with a comment. Recommend the signature change: both sides are in this repository, and a parameter the implementation ignores is a trap for the next reader.
3. **Best solution:** as amended; it is also what group D needs.

Size S. Risk: none beyond the test `LotQuestionsForCatalogue` has.

### RLS-16 (low) BUILD AS SUGGESTED

1. **Valid: YES.** `security.user_belongs_to_entity` (m1security V0016:89-90): `kernel.scope_entity() <> p_entity_id AND ...` is NULL for a NULL scope entity, the `IF` is skipped. Reachability: `ScopeConnectionCustomizer` sets the entity from `scope.entityId()` for OWN; `JdbcUserScopes` reads it from `user_role.scope_entity_id` (NOT NULL by M1's schema), so an OWN scope with no entity should not occur; the function must still not depend on that.
2. **Suggested fix: CORRECT.** m1security `V0018`: `CREATE OR REPLACE` with `kernel.scope_entity() IS DISTINCT FROM p_entity_id`; while there, wrap the whole test in `coalesce(..., false)` as `scope_is_federation` does in the same file, and set `search_path` with `pg_temp` (RLS-13).
3. No alternative worth naming.

Size S. Risk: none.

### RLS-13 (low) BUILD AS SUGGESTED

1. **Valid: YES.** PostgreSQL searches the session's temporary schema before `pg_catalog` unless `pg_temp` is listed explicitly, and `grep search_path` over the migrations shows every SECURITY DEFINER function but two (`notification_recipients`, `appointed_officer_name`) ends in `<schema>` with no `pg_temp`. `01-roles.sh` revokes nothing, so app_rw keeps TEMPORARY. Impact as the verifier says: control flow of a maintenance function, bounded.
2. **Suggested fix: CORRECT.** One migration per module folder with `ALTER FUNCTION <schema>.<fn>(<args>) SET search_path = pg_catalog, <schema>[, party, kernel], pg_temp` for each definer function (no body change); `REVOKE TEMPORARY ON DATABASE coop_erp FROM PUBLIC` in `01-roles.sh` (idempotent; nothing in `backend/app/src` creates a temp table). Add to `SchemaRulesIntegrationTest`: every `prosecdef` function's `proconfig` has a `search_path` whose last element is `pg_temp` (the only rule that keeps the next function honest).
3. No alternative.

Size S (mechanical; about 30 functions). Risk: none; `ALTER FUNCTION ... SET` does not touch bodies or grants.

### RLS-14 (low) BUILD AMENDED

1. **Valid: YES.** `kernel.change_log_purge` (V0082:80-142) trusts both bounds, runs as the migrator, is granted to app_rw with no class test; `ChangeLogPurgeJob` calls it in `SystemScope.federationView()` with the configured retention and yesterday UTC.
2. **Suggested fix: NEEDS CHANGES.** Reading `sync.change_log.retention` inside the function means the definer reading `kernel.config_value`, which has no app_seed policy (V0052: own_read, fed_view, ext_view only), so it would need a kernel policy for app_seed and a copy of `ConfigRegistry`'s scope resolution in SQL. Not worth it. Corrected, kernel `V0085`: require `kernel.scope_class() = 'FEDERATION_VIEW'` (the class the job uses; a NONE or OWN caller is refused), clamp `p_keep_apply_from <= current_date` (the 9999-12-31 case: the future-dated rows a full snapshot needs are then always kept), keep the 24-hour floor. The retention itself stays the job's argument: the register's lower bound is the 24 hours the function already enforces.
3. **Best solution:** as amended. Alternative rejected: the function computing both bounds itself (needs the config read above).

Size S. Risk: the job's test (`purgeOlderThan` with a short retention) runs in FEDERATION_VIEW already.

## Fix group C: only the Federation writes a ceiling (RLS-04, RLS-05)

**Decide first:** nothing (23A section 3 says "always the Federation"; the small question in RLS-05 can be decided on delegation, D-3).

### RLS-04 (medium) BUILD AMENDED

1. **Valid: YES for control_price and advisory_read; NO for mrp_policy.** `own_write`/`own_update` on `pricing.control_price` (m3 V0005:36-40) admit any OWN entity; `ControlPriceStore.ceilingFor` selects by `sku_id` with no owner filter, so a society's row is a ceiling for every till; the gist exclusion then refuses the Federation's own entry. The guard is `EnterControlPriceHandler:86` only. `advisory_read` (V0005:82-92) and `PriceListStore.advisoryInForce` read any entity's PUBLISHED ADVISORY list; `CreatePriceListHandler:80` is the only guard. **mrp_policy is not Federation-only**: 23A section 3 gives it `owner_entity_id` with `UNIQUE (sku, tag, owner)`, V0005's comment says "a society's effective policy falls back to the Federation's row", and the demo gives `prc.mrp_policy.set` to the society manager (role 332). Only its deferred tag scope is Federation-only. Leave mrp_policy alone.
2. **Suggested fix: NEEDS CHANGES** (drop the mrp_policy clause; fix the matrix exception properly). m3pricing `V0006`:
   - `ALTER POLICY own_write ON pricing.control_price WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity() AND owner_entity_id = (SELECT kernel.system_entity()))`; the same two clauses on `own_update` USING and WITH CHECK. Keep the `scope_entity()` test: `OwnPoliciesTestTheClassIntegrationTest` requires the class test and the template requires the entity test; the Federation acting entity-wide is the only caller that passes all three (the `fed_admin` form of CR-21A-1 item 2).
   - `ALTER POLICY everyone_reads ON pricing.control_price USING (kernel.scope_class() <> 'NONE' AND owner_entity_id = (SELECT kernel.system_entity()))`: a row that somehow is not the Federation's is a ceiling for nobody.
   - `advisory_read` on `price_list`: add `AND owner_entity_id = (SELECT kernel.system_entity())`; on `price_list_line`: `AND l.owner_entity_id = (SELECT kernel.system_entity())` inside the EXISTS.
   - `RlsMatrixIntegrationTest.EXCEPTIONS`: replace `everyClassButNoneReadsEverything` for `pricing.control_price` with a new predicate `federationRowsOnly`: the matrix's made-up row is owned by A, not the Federation, so every SELECT is HIDDEN and every INSERT/UPDATE REFUSED; and add one M3 test that the Federation's row (owner = `kernel.system_entity()`, which the test fixture configures) is read by OWN, PARTY, FEDERATION_VIEW and EXTERNAL and not by NONE.
3. **Best solution:** as amended. The Federation's handler guard stays (template: "the handler guard stays"). Alternative rejected: a trigger comparing with `system_entity()`; the policy is where the template puts it and the test already reads policies.

Size S. Risk: with no system entity configured, `system_entity()` is NULL and nobody reads control prices (fails closed, the V0061 rule); every environment that sells sets `coop-erp.system.entity-id`, and the demo does.

### RLS-05 (low) BUILD AMENDED

1. **Valid: YES.** `buyer_read` on `pricing.price_list` (V0003:103-111) joins the relationship on list, seller and buyer only; m1party V0011 admits SUSPENDED and REPLACED.
2. **Suggested fix: CORRECT, with the status set decided.** Same m3pricing `V0006`: `AND r.status = 'ACTIVE'`. `party.entity_relationship` has no validity dates in V0007 (only `status`), so there is no range to add. `price_list_line.buyer_read` needs no change: its EXISTS on `pricing.price_list` runs under the caller's policies and follows the list.
3. **D-3, on delegation:** recommend ACTIVE only. A suspended buyer cannot order (M4 guards the relationship), its open invoices carry their own prices, and `party_names_read` already uses ACTIVE. Alternative rejected: `IN ('ACTIVE','SUSPENDED')` so a suspended buyer can still see the list it was invoiced at: the invoice is the record of that, not the live list.

Size S. Risk: none known; M4's order pricing runs in the buyer's scope against an ACTIVE relationship.

## Fix group D: the batch correction trigger (RLS-07, with RLS-12)

**Decide first:** D-4 (where the "lot holder" fact is asked in SQL).

### RLS-07 (medium) DECIDE THEN BUILD

1. **Valid: YES.** `catalogue.batch_apply_correction` (m2 V0004:70-93) is SECURITY DEFINER, asks only "REGISTERED, same SKU", never who the caller is; any OWN society's insert of a correction row (own_write) supersedes another entity's batch and re-points `batch_key` for everyone. The Java guard (`CorrectBatchHandler:79`: Federation or lot holder) is the only check; the migration's own comment ("the database cannot ask M5") predates m5 V0002.
2. **Suggested fix: NEEDS CHANGES** in how the lot question is asked, and the finding is right that this is a decision. The trigger runs in the caller's session (the GUCs are visible) as the migrator, so it can test `kernel.scope_class() = 'OWN'` and compare `kernel.scope_entity()` with the corrected batch's `owner_entity_id` and `(SELECT kernel.system_entity())` with no cross-module question at all. The third case, the lot holder, is the whole difficulty. Options:
   - (i) **The trigger calls M5's function** `inventory.entity_holds_lot_of(NEW.corrects_batch_id, kernel.scope_entity())` (after RLS-12 it is executable by the migrator only). M2's SQL names M5's function: upward in the module layering, but it is a read of a function M5 created *for M2's questions* (m5 V0002's own comment), the SQL form of `InventoryLotQuery`, which M2 publishes and M5 implements. `tools/check-schema-ownership.mjs` does not refuse a call in a function body (m3 V0003 names `party.entity_relationship` in a policy and passes). The body must be plpgsql (it is), so a fresh database that runs the m2 stream before m5 still creates it; the call resolves at run time.
   - (ii) **The trigger enforces owner-or-Federation only**, and the lot-holder path stays a Java guard. This refuses the legitimate holder's correction at the database, so the holder path would have to go through a different door (a Federation-run correction, or an M5 command) and doc 22 B-I8 says the holder corrects.
   - (iii) **A kernel function or an M5-installed hook in the catalogue schema.** Both write another module's schema or put lots in the kernel; rejected.
   - (iv) **A holder projection in M2** fed by M4/M5 events: eventually consistent, so the guard would be flaky right after a GRN; rejected.
3. **D-4, recommended:** option (i), recorded as an accepted, documented exception to the layering rule for SQL: *a module's migration may call a function another module created for it, read-only, never a table and never a write; the function is part of that module's contract and is listed in its README.* Then m2catalogue `V0008` `CREATE OR REPLACE FUNCTION catalogue.batch_apply_correction()` with, before the two updates:
   ```sql
   IF kernel.scope_class() <> 'OWN'
      OR NOT (corrected.owner_entity_id = kernel.scope_entity()
              OR kernel.scope_entity() = (SELECT kernel.system_entity())
              OR inventory.entity_holds_lot_of(NEW.corrects_batch_id, kernel.scope_entity())) THEN
       RAISE EXCEPTION 'm2.batch.not_holder' USING ERRCODE = 'insufficient_privilege';
   END IF;
   ```
   (`corrected` read first through `correction_read`), `search_path = pg_catalog, catalogue, pg_temp`. The Java guard stays. Order of work: RLS-12 (m5 V0007) first, so the two-argument function is no longer callable by app_rw when the trigger starts relying on it.

Size M. Risk: a correction by a lot holder in a location-scoped session: `entity_holds_lot_of` tests the entity only, so it still passes; the trigger must not add a location test (a batch is global). Test: `CatalogueBatchesIntegrationTest` (or its equivalent) gains the three callers: owner, Federation, holder, plus a society with no lot refused by the database when the Java guard is bypassed (insert directly).

## Fix group E: extension rows after issue (RLS-09)

**Decide first:** D-5 (the rule: "written before issue, or in the transaction that issues").

### RLS-09 (medium) BUILD AMENDED; the suggested fix is WRONG as written

1. **Valid: YES.** The ten V0001 extension tables and V0008's claim tables have `document_write WITH CHECK (kernel.document_owned(document_id))` only; `doc_grn_line` and `doc_grn` have `document_update` on the same test with `UPDATE (batch_id, unit_cost)` and `UPDATE (confirmed_by, confirmed_at)`; no trigger refuses a write after issue. `kernel.document_line` has had `document_unissued` and `trg_document_line_after_issue` since V0055. A receiver's OWN session may insert a `doc_grn_line` into a confirmed GRN or rewrite its `unit_cost` at any later time, against "nothing on a confirmed GRN is ever edited".
2. **Suggested fix: WRONG as written, and the verifier undercounts the breakage.** A plain `document_unissued` test breaks not two handlers but nine: every M4 handler that issues and then writes its extension row in the same transaction: `ConfirmGrnHandler` (:169 then :199, :218; and the discrepancy at :317 then :323, :335), `IssueInvoiceHandler` (:215 then :219), `IssueCreditNoteHandler` (:119, :125), `SettleDiscrepancyHandler` (:189, :197), `ApproveClaimHandler` (:169, :174), `RaiseClaimHandler` (:193, :199, :211), `RecordPaymentReceiptHandler` (:154, :158), `RecordChequeOutcomeHandler` (:158, :163). Reordering them all is a large change, and for the GRN it is impossible: the synthetic batch number is `S-<GRN number>-<line>` (doc 22 section 3.7), so the batch, and with it `doc_grn_line.batch_id`, exists only after the number is drawn (CR-24A-1 item 4). Corrected fix:
   - kernel `V0084`: `kernel.document_open_for_write(p_document_id uuid) RETURNS boolean`, SECURITY INVOKER, STABLE: `EXISTS (SELECT 1 FROM kernel.document d WHERE d.document_id = p AND (d.issued_at IS NULL OR d.xmin = pg_current_xact_id()::xid))`. The header row's `xmin` is the id of the transaction that last wrote it; after `issuance.issue`'s UPDATE in this transaction it equals the current transaction's id, so "issued in this transaction" is a fact the database knows without a session variable (which the template forbids) and without trusting clocks (`issued_at` comes from the Java clock, fixed in tests). Caveat to prove in the test: the cast `xid8 -> xid` exists in PostgreSQL 16 (`pg_current_xact_id()::xid`); if not, compare `d.xmin::text::bigint` with `txid_current() & 4294967295`. Second caveat: a savepoint (subtransaction) gives the tuple the subtransaction's xid; Spring's default propagation uses none, and the kernel's `inOwnTransaction` opens a new connection, not a savepoint.
   - m4trading `V0009`, per table, not by copy: `ALTER POLICY document_write ... WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id))` on `doc_order`, `doc_order_line`, `doc_delivery`, `doc_delivery_drop`, `doc_delivery_line`, `doc_grn`, `doc_grn_line`, `doc_discrepancy`, `doc_discrepancy_line`, `doc_invoice`, `doc_credit_note`, `doc_payment_receipt`, `doc_claim`, `doc_claim_line` (every insert happens before or in the issuing transaction: `CaptureGrn`, `CreateOrder`, `CreateDeliveryNote` before; the nine above in it). **Not** on `trading.claim_photo`: `AddClaimPhotoHandler:79` adds photos to an issued claim, as `kernel.document_attachment` admits attachments after issue. `document_update` gains the same clause on `doc_grn` and `doc_grn_line` only (both columns are written in the confirming transaction). The post-issue column updates stay as they are, because they are legitimate by design: `doc_order_line.cancelled_qty` (CancelOrder, AmendOrder), `doc_delivery` dispatch columns and `doc_delivery_drop` POD columns (24A), `doc_invoice.settled_amount/credited_amount` (caches recomputed from links), the three `print_object_key`s. Add a `BEFORE INSERT` trigger with the same test on `doc_grn_line` only (the one table whose rows are money and stock), as V0055 did for `kernel.document_line`, so the rule holds for every role.
   - `payment_allocation`, `cheque`, `cheque_outcome`, `invoice_dispute`, `claim_decision(_line)`, `discrepancy_settlement`, `claim_return` are the other party's own rows or insert-only facts about an issued document; they are not extension rows and are out of this fix.
3. **D-5, recommended:** *an extension row of a document is written before the document is issued or in the transaction that issues it, never later; columns a handler specification writes after issue are named per table in the grant and keep their `document_update` policy.* Alternative rejected: reorder the nine handlers to write before issue (impossible for the GRN's batch id; large; and the kernel's own protocol issues last for a reason, V0055 item 1). Record it in `RLS_POLICY_TEMPLATE.md` under "The rows of a document" and in the M4 README.

Size M (one kernel helper, one M4 migration, one trigger, a test per handler that the write still passes, and RLS-17's read-side matrix). Risk: a handler that writes an extension row in a transaction other than the issuing one would start failing; the grep above found none, and the test suite exercises all nine.

## Fix group F: a shop sees its own shop (RLS-08, RLS-11, RLS-15)

**Decide first:** D-6 (trading projections carry the document's location).

### RLS-08 (medium) DECIDE THEN BUILD, one fix with M8-07

1. **Valid: YES.** `reporting.trade_document_event`, `trade_line_fact` (m8 V0002) and V0004's tables have no location column; own_read/party_read test the entity. `kernel.document` has refused a shop the entity's other documents since V0061. The demo's location-scoped assignments (users 203, 212, 222, 233, 243, 253) hold roles without `rpt.report.run`, but a location-scoped assignment of any role that has it (the manager roles do) reaches the tables, and nothing prevents such an assignment.
2. **Suggested fix: CORRECT in the first option, and the second option should be rejected.** A guard in `TileSources`/`ReportSources` ("entity-wide scope only") is application code filtering by tenant, which AGENTS.md idea 1 forbids. m8reporting `V0007`: `ADD COLUMN location_id uuid` (nullable) to the five trading projection tables; `ALTER POLICY own_read` to the template's location line and `party_read` to the owner-side line (V0055 item 4's form); the consumers write it from the events that carry one (`GrnConfirmed.receiverLocationId`, `DiscrepancyRaised.receiverLocationId`, `ClaimRaised.locationId`, `ClaimReturnDispatched.locationId`; orders, deliveries, invoices and payments are entity-wide today and leave it NULL, as their `kernel.document.location_id` is). A NULL-location row is then invisible to a shop session and visible to the entity-wide one, which is exactly V0061's rule for the documents themselves. Existing rows: every projection "can be dropped and rebuilt" (M8 README, "Rebuilding a projection"); the fix PR says so and the demo rebuilds the two consumers, or the rows stay NULL (visible entity-wide only) until the next rebuild, which is safe in the wrong direction.
3. **D-6, recommended:** *a projection of a document carries the document's `location_id` and the template's location line; a shop reads the trading picture of its own shop, as it reads its own documents.* Alternative rejected: the code guard (above). Documents: 28A section 3 (`trade_document_fact` gains `location_id`), already departed from by V0002's event-per-row design; one CR covers both.

Size M (shared with M8-07). Risk: the `OwnPolicies` location test will now require the location line in `own_write` WITH CHECK on these tables (it checks every write policy on a table with a `location_id` column), which the consumer satisfies because it writes in the event owner's entity-wide scope.

### RLS-11 (low) BUILD AS SUGGESTED

1. **Valid: YES.** `party_read` on `trading.claim_return` (V0008:156-159): the PARTY branch has no owner-side location line; the template and `kernel.document` (V0055 item 4) have it. The customizer passes `scope.locationId()` for PARTY too (ScopeConnectionCustomizer:51), so a location-scoped PARTY session is possible.
2. **Suggested fix: CORRECT.** m4trading `V0009` (same file as group E): recreate `party_read` with `(kernel.scope_class() = 'PARTY' AND ((owner_entity_id = kernel.scope_entity() AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location())) OR counterparty_entity_id = kernel.scope_entity())) OR (kernel.scope_class() = 'OWN' AND counterparty_entity_id = kernel.scope_entity())`. The OWN branch's deliberate narrowing (own rows through own_read only) stays; the migration comment of V0008 explains it.
3. No alternative.

Size S. Risk: none; the matrix has no PARTY-at-a-location cell yet (RLS-17 d adds it).

### RLS-15 (low) DEFER, with the fix named

1. **Valid: PARTLY.** The policy text is as stated: `inventory.pick_list` has no location, own_update tests the entity, `UPDATE (status, dispatched_at)` is granted. But the only writer is `DispatchDeliveryHandler:90`, reached from `DeliveryConsumer.onDispatched` in the event owner's scope set by `EventConsumerDispatcher.applyScope` (OWN, from the event), never from a user's session; `whs.pick` is ENTITY-scoped in 25A section 10 (:240) and `OverridePick` is "warehouse scope". So a shop session reaches the row by SQL only. **The verifier's second option (`kernel.scope_location() IS NULL` on own_update) would break the demo:** the stores roles 303, 312, 322 are assigned at the warehouse location (users 203, 212, 222) and pick and dispatch from there, and the consumer's scope may carry the event's location.
2. **Fix when built** (m5inventory, after the pick-list override ticket): `own_update USING (... AND EXISTS (SELECT 1 FROM inventory.pick_list_line l WHERE l.pick_list_id = pick_list.pick_list_id))`: the subquery runs under the caller's own policies, so a location-scoped session updates only a pick list it can see a line of (a line at its location), an entity-wide session any; the same trick as `account_holder_read`. No new column (a pick list may be picked from several stores, so a header location is not well defined). Add a matrix exception (the made-up header has no lines: OWN UPDATE refused).
3. **Why DEFER:** no API path, a harm limited to the entity's own reservations, and the pick-list override (25A `OverridePick`, deferred) will reopen this table's policies anyway. Record it in the M5 README's Deferred list.

Size S when built. Risk: a pick list whose every line is short (no lot, `location_id` NULL) becomes updatable entity-wide only; acceptable.

## Fix group G: what the counterparty may see (RLS-10)

### RLS-10 (low) DECIDE THEN BUILD

1. **Valid: YES.** `trading.v_document_party` exists in the template's text only; PARTY readers get whole rows of `order_allocation(_line)`, `claim_decision(_line)`, `discrepancy_settlement` and every extension table through `document_visible`. Doc 18 section 3.7 promises masking of "cost, margin and internal notes". Which of the named columns are internal is not settled: `override_reason` and `lock_override_by` read as the seller's own; `reason_code/reason_text` on `order_allocation` are the rejection the buyer must see (CR-24A-1); `doc_discrepancy.arbitration/proposal` are the two parties' settlement; `claim_decision.findings` versus `reason` is ambiguous in 24A.
2. **Suggested fix: NEEDS CHANGES.** A masking view does not fit how M4 reads: every M4 query reads the base tables under RLS, and the same role (app_rw) serves OWN and PARTY, so column grants cannot separate them either. The workable form is structural, as M8 did ("masking is structural", m8 V0002 comment): move the internal columns to a seller-only table (`trading.order_allocation_note (order_line_id PK, override_reason, lock_override_by, owner_entity_id)` with own_* only), copy the data in the migration, drop the columns. That is a new m4trading migration plus `AllocationReads` changes.
3. **D-7, recommended:** *the per-column list in the M4 README and a CR against 24A section 3: internal = `order_allocation_line.override_reason`, `order_allocation.lock_override_by`; shared = everything else named.* Then build the structural move. Alternative rejected: the view (above). Not urgent: the only reader of those two columns is the seller's own screen.

Size M. Risk: low; the columns are nullable and read in one place each.

## Fix group H: the tests that would have caught this (RLS-17)

### RLS-17 (low) BUILD AS SUGGESTED, plus two gaps

1. **Valid: YES**, every item. `ownedTables()` (RlsMatrix:828-842) keys on `owner_entity_id`; the write-policy test concatenates qual and with_check and accepts either substring (OwnPolicies:110-118); the location test checks WITH CHECK only (:56-77); B has one location; the control_price exception asserts OWN(A) INSERT = done.
2. **Suggested fix: CORRECT**, and add: (f) a read-side matrix for the document-extension tables driven by a made-up `kernel.document` header (OWN at the header's location, PARTY as counterparty, FEDERATION_VIEW, EXTERNAL by grant, NONE), which also proves group E's `document_open_for_write` (insert under an unissued header, under a header issued in the same transaction, and under one issued in an earlier transaction, the last refused); (g) `SchemaRulesIntegrationTest`: every SECURITY DEFINER function's `search_path` ends in `pg_temp` (RLS-13), and every SECURITY DEFINER function granted to app_rw names `kernel.scope_class()` in its body (group B's rule; `prosrc` text check, the same kind of check as OwnPolicies).
3. **Order:** after groups A to F merge, because the EXCEPTIONS change with them (control_price's predicate, the three customers tables, pick_list later).

Size M, test code only. Risk: none to production.

## Decisions to record (recommended, for the architect to accept or amend)

- **D-1 Member identity and the view-all classes (RLS-01, RLS-02).** Decision: FEDERATION_VIEW and EXTERNAL_TIMEBOXED do not read `customers.customer`, `customer_phone`, `customer_consent` by policy; they read the credit book (accounts, postings, allocations, adjustments, history) as today; identity reaches the Federation or a regulator through a module's audited query or export when one is specified. Rejected: keeping doc 18's "Everything" for personal data (unaudited SQL reads, against doc 27 section 9.3 and PDPA minimisation while L-01/L-02 are open); a definer masking view (no consumer). Documents: CR to doc 18 section 3.7 (carve-out), doc 27 sections 5.2 and 9.5.
- **D-2 Cross-tenant functions (RLS-03, -06, -12, -14, -16).** Decision: a SECURITY DEFINER function callable by app_rw tests the scope class first, takes the caller from `kernel.scope_entity()`, never takes the entity it answers for as a parameter, returns a boolean, a count or the caller's own ids, and sets `search_path` with `pg_temp` last; recorded as a fifth case in `RLS_POLICY_TEMPLATE.md` and pinned by `SchemaRulesIntegrationTest`. Rejected: per-function grants as the control (one role serves every class).
- **D-3 A suspended buyer and the trade list (RLS-05).** Decision: `buyer_read` admits ACTIVE relationships only. Rejected: admitting SUSPENDED (the invoice records the price; `party_names_read` uses ACTIVE).
- **D-4 Where the lot-holder question is asked (RLS-07).** Decision: `catalogue.batch_apply_correction` calls `inventory.entity_holds_lot_of`, M5's function created for M2's question, as a recorded exception to the layering rule for SQL: a read-only call to a function another module created for the caller, never a table, never a write, listed in that module's README. Rejected: owner-or-Federation only (refuses the holder, doc 22 B-I8); a kernel function or an M5 hook in the catalogue schema (writes another module's schema); a holder projection (eventually consistent).
- **D-5 Extension rows after issue (RLS-09).** Decision: an extension row is written before issue or in the issuing transaction, enforced by `kernel.document_open_for_write` (header `xmin` equals the current transaction); columns written after issue are named per table (order_line cancellation, delivery dispatch and POD, invoice caches, print keys) and keep their own policy; `claim_photo` follows attachments. Rejected: reordering the nine handlers (impossible for the GRN's batch id); a session variable (template rule); clock comparison (Java clocks).
- **D-6 Trading projections per shop (RLS-08, with M8-07).** Decision: the five trading projection tables carry the document's `location_id` and the template's location line; rows of entity-wide documents stay NULL and are read entity-wide only. Rejected: a code guard in TileSources/ReportSources (application code filtering by tenant).
- **D-7 PARTY-visible columns (RLS-10).** Decision: a per-column list in the M4 README (internal: `override_reason`, `lock_override_by`), implemented by moving internal columns to a seller-only table. Rejected: a masking view (every M4 query reads base tables; one role for OWN and PARTY).
- **Numbering (all groups).** The demo server holds data under strict Flyway, so kernel migrations are numbered above `V0083` from now on; the lane table in `kernel/README.md` is history and should say so (and lose its conflict markers).

## Suggested fix PRs, in order

1. `fix(m5)`: RLS-12, RLS-13 (m5 part) — unblocks group D.
2. `fix(m2)`: RLS-07 (after D-4), RLS-13 (m2 part).
3. `fix(m7)`: RLS-02 (after D-1), RLS-03, RLS-13 (m7 part); RLS-01's RLS half rides here, the hash in M7CR-01's PR.
4. `fix(m3)`: RLS-04, RLS-05 (D-3), RLS-13 (m3 part).
5. `fix(m1)`: m1party `caller_trades_with` (for RLS-06), RLS-16, RLS-13 (m1 parts).
6. `fix(m9)`: RLS-06 with M9-06.
7. `fix(kernel)`: RLS-09 helper (V0084), RLS-14 (V0085), RLS-13 (kernel part), README conflict markers.
8. `fix(m4)`: RLS-09 policies and trigger, RLS-11.
9. `fix(m8)`: RLS-08 with M8-07 (after D-6).
10. `test(kernel)`: RLS-17, last.
11. RLS-10 after D-7; RLS-15 with the pick-list override ticket.

## Findings I could not settle

- **RLS-09, the `xid8 -> xid` cast.** `pg_current_xact_id()::xid` should exist in PostgreSQL 16; the kernel test for `document_open_for_write` settles it, with `d.xmin::text::bigint = txid_current() & 4294967295` as the fallback.
- **RLS-08 and RLS-15 reachability** depend on which real role templates (not the demo seed) are assignable at a location with `rpt.report.run` or `whs.pick`; the M1 template seed was not read. The fixes above do not depend on the answer.
- **RLS-06**: whether `caller_trades_with` should admit SUSPENDED relationships for notifications (recommended yes) is for M9's owner to confirm against doc 29's rule catalogue.
- **RLS-13's `REVOKE TEMPORARY`**: whether any tool outside `backend/app/src` (the demo loader, the till simulator, Testcontainers fixtures) creates a temporary table as app_rw; a grep of `backend/app/src` found none, the rest was not read. The `pg_temp` change alone closes the finding; the revoke is belt and braces.
- **Whether the demo server's database will be kept** across the fix releases (it decides V0084+ versus lane numbers for the kernel). `docs/DEMO_SETUP.md` says keep it; if it is reset instead, lane numbers would still work, but V0084+ is safe either way.
