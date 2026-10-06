# The row-level security policy template

The five classes of doc 18 §3.7, as SQL to copy into a module migration (17A §6.3, completed by
19A K-01). `RlsMatrixIntegrationTest` reads the SQL block below from this file and proves every row of
the matrix against it; it then runs the same matrix against every real table that has an
`owner_entity_id` column, and a table that departs from the template is listed in the test's
`EXCEPTIONS` with the reason. A departure is a decision to write down, not a habit.

Every operational table has: `ENABLE` and `FORCE ROW LEVEL SECURITY`; `own_read` and
`own_write`; `own_update` if the table grants `UPDATE` to `app_rw`, and `own_delete` if it
grants `DELETE` (a table that grants neither is append-only and needs neither; under `FORCE`,
a granted `UPDATE` or `DELETE` with no policy silently changes zero rows); `fed_view`;
`ext_view`. A table with a counterparty (a document: seller and
buyer) has `party_read` as well. Nothing else, and never a policy that trusts a session
variable other than the four the kernel sets (`kernel.scope_entity()`,
`kernel.scope_location()`, `kernel.scope_class()`, `kernel.granted_entities()`): any code can
set a session variable, so a policy that reads one is a policy that admits everyone.

```sql
ALTER TABLE <schema>.<table> ENABLE ROW LEVEL SECURITY;
ALTER TABLE <schema>.<table> FORCE ROW LEVEL SECURITY;

-- OWN: the caller's entity, and its location when the assignment is location-scoped. A table
-- with no location_id column leaves the location line out. The class test is what keeps a
-- FEDERATION_VIEW, EXTERNAL or NONE caller, whose scope entity is also set, out of own_*:
-- doc 18 section 3.7 says those classes write nothing and EXTERNAL reads its grant only
-- (17A section 6.3 and 19A section 1 leave the test out; CR-17A-3 puts it in).
CREATE POLICY own_read ON <schema>.<table> FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

-- INSERT: the same rows own_read shows, so a shop-scoped session writes only at its own
-- location, never a row at a sibling shop that it could not read back (doc 18 section 3.7, M-05;
-- PLAN_TO_M2 6.12, decided 27 September 2026). An entity-wide session writes anywhere in its
-- entity. A shop-scoped session cannot write a row with no location either: that is entity-wide
-- work (kernel.numbering_series is the one departure, for the ENTITY series; kernel V0064).
CREATE POLICY own_write ON <schema>.<table> FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

-- UPDATE and DELETE, where the table grants them: the rows own_read shows, and (WITH CHECK)
-- an update cannot hand a row to another entity, nor move it to another location. Every clause
-- carries the class test.
CREATE POLICY own_update ON <schema>.<table> FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_delete ON <schema>.<table> FOR DELETE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

-- PARTY, on tables with a counterparty column only: the seller reads the buyer's side of the
-- document and the buyer the seller's. What a PARTY caller may see of the row is decided by
-- the masking view it reads through (trading.v_document_party, M4: no cost, margin or internal
-- notes), never by this policy, which decides which rows. OWN is admitted here too, so a
-- caller in OWN scope reads its documents through the same view. The location line applies
-- on the owner's side only: it keeps a shop-scoped user to its shop's own documents (doc 18:
-- a shop sees nothing of a sibling shop), while location_id is the owner's location and says
-- nothing about the counterparty's, so a shop-scoped counterparty still sees the documents it
-- is the buyer of (19A section 1 leaves the location out altogether; CR-17A-3).
CREATE POLICY party_read ON <schema>.<table> FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND ((owner_entity_id = kernel.scope_entity()
                 AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                OR counterparty_entity_id = kernel.scope_entity()));

-- FEDERATION_VIEW: everything, read only.
CREATE POLICY fed_view ON <schema>.<table> FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

-- EXTERNAL_TIMEBOXED: a regulator or auditor reads the entities of its grant, read only. The
-- time box is not in the policy: kernel.granted_entities() is what the scope customizer set,
-- and it sets only the entities of the caller's grants that are ACTIVE and inside
-- [valid_from, valid_until) now (JdbcUserScopes, from security.external_grant). An expired or
-- revoked grant gives an empty array, which reads nothing; ExternalGrantsIntegrationTest
-- (scenario65TheRegulatorGrantLifecycle, anExpiredOrEmptyGrantReadsNothing) proves it.
CREATE POLICY ext_view ON <schema>.<table> FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));
```

What the matrix guarantees, and the test proves:

| Class | Reads | Writes |
|---|---|---|
| OWN, entity-wide | rows owned by the scope entity, and rows where it is the counterparty | inserts, updates and deletes rows owned by the scope entity; never moves one to another owner |
| OWN, at a location | the entity's rows at that location, and rows where it is the counterparty | inserts, updates and deletes the entity's rows at that location; never inserts a row at another location or with none, never moves one to another location |
| PARTY | rows it owns (at its location, if it has one), and rows where it is the counterparty | nothing |
| FEDERATION_VIEW | every row | nothing |
| EXTERNAL_TIMEBOXED | rows owned by a granted entity; nothing with an empty grant | nothing |
| NONE (even with a scope entity and a grant naming it), or a transaction that forgot the scope | nothing | nothing |

Three cases the template does not cover, and what does:

- **Reference data and projections** (a permission catalogue, a directory maintained by a
  trigger) belong to no tenant. They get a read policy for the classes that may read them and
  `seed_reference`/`projection_write` policies **`TO app_seed`** for the writer (kernel `V0005`;
  the migrator is a member): a role, not a variable.
- **The Federation acting on rows it does not own** (registering an entity, activating it):
  the guard in the handler decides (`FederationCaller` in M1 is the example), and the
  `federation_*` policies of `party.entity` carry it in SQL. That is the fifth class,
  `fed_admin`, which `CR-21A-1` item 2 (accepted 27 September 2026) adds to 17A section 6.3:
  a `federation_*` policy is written only where the implementation guide says the Federation
  administers rows it does not own, and the handler guard stays. A policy that must know which entity is the Federation
  asks `(SELECT kernel.system_entity())` (kernel `V0061`: the copy of
  `coop-erp.system.entity-id` the platform writes on every start; NULL, so admitting nothing,
  when none is configured), never a session variable and never a table of its own.
  `catalogue.sku` is the example: only the Federation's rows are SHARED (m2catalogue `V0006`);
  the `federation_*` policies of `party.entity` and M1's security functions ask the same since
  m1party `V0012` and m1security `V0016`, which retired M1's copy `party.federation_identity`.
- **A user's own rows regardless of tenant** (the idempotency key): a policy on
  `app.user_id`, which the customizer sets for the request's user (kernel `V0010`).

A fourth case, from K-07:

- **The rows of a document** (`kernel.document_line`, `document_link`, `document_state_history`,
  `document_attachment`, and a module's extension table keyed on `document_id`) carry no
  owner column: the header decides. They get `document_read` on
  `kernel.document_visible(document_id)` and `document_write` on
  `kernel.document_owned(document_id)`, two `SECURITY INVOKER` functions that ask the header
  under the caller's own policies. So a row is visible under whichever class sees its document
  (OWN, PARTY through the counterparty, FEDERATION_VIEW, EXTERNAL through the grant) and
  writable only by the owner in an OWN scope, with nothing copied and nothing to keep in step.
  **An extension row is written before the document is issued or in the transaction that issues
  it, never later** (wave 2, RLS-09; `docs/progress/deviations/2026-10-06-wave2-extension-rows-after-issue.md`):
  `document_write` adds `kernel.document_open_for_write(document_id)` (kernel `V0086`,
  `SECURITY INVOKER`): the header is unissued, or its `xmin` is the current transaction's id,
  which is how the database knows "issued in this transaction" without a session variable
  (forbidden above) and without trusting a clock. The columns a handler specification writes
  after issue (a cancelled quantity, dispatch and proof-of-delivery columns, the invoice's
  settlement caches, a print key) are named per table in the `UPDATE` grant and keep their own
  `document_update` policy; attachments and photographs may be added to an issued document.
  A savepoint gives a row the subtransaction's id, so the issuing handler opens none (Spring's
  default propagation; the kernel's `inOwnTransaction` opens a connection, not a savepoint).

A fifth case, from wave 2 of the code review (RLS-03, -06, -12, -14, -16;
`docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md` (1)):

- **A function that answers across tenants.** A guard sometimes needs one fact about rows its
  caller may not read (does anyone hold this phone number; does the caller hold a lot of this
  batch; does the caller trade with this entity; how many users of this entity hold a role). That
  fact is answered by a `SECURITY DEFINER` function the migrator owns, executable by `app_rw`,
  which reads through a policy `TO app_seed` and follows five rules: (1) it tests
  `kernel.scope_class()` first and answers nothing (false, zero, no rows) to a class that has no
  business asking, today `= 'OWN'`; a job's class is named explicitly when a job calls it
  (`kernel.change_log_purge` takes `FEDERATION_VIEW`); (2) the caller is `kernel.scope_entity()`,
  never a parameter: parameters name the thing asked about, never the entity answered for;
  (3) it returns the smallest fact the guard needs, a boolean, a count, or ids the caller already
  owns, so another tenant's ids never leave it; (4) `SET search_path = pg_catalog, <schema>, pg_temp`,
  with `pg_temp` last, so a session cannot shadow a catalogue relation with a temporary table
  (PostgreSQL's own guidance for definer functions; `01-roles.sh` revokes `TEMPORARY` from
  `PUBLIC` besides); (5) `REVOKE ALL FROM PUBLIC; GRANT EXECUTE TO app_rw`: who may call is the
  class test inside, not the grant, because one role serves every class. Examples:
  `party.caller_trades_with(entity)`, `party.trading_standing(entity)` (m1party),
  `inventory.caller_holds_lot_of(batch)`, `inventory.sku_has_lot(sku)` (m5inventory),
  `security.user_belongs_to_entity(user, entity)` (m1security). `SchemaRulesIntegrationTest` pins
  rule (4) on every definer function of the schemas fixed so far.
  One recorded exception to the layering rule, for SQL only: a module's migration may call a
  function another module created for it, read-only, never a table and never a write, and the
  creating module lists the function in its README as part of its contract
  (`catalogue.batch_apply_correction` asks `inventory.entity_holds_lot_of`, m2catalogue `V0008`).
