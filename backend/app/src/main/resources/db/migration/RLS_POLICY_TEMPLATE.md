# The row-level security policy template

The five classes of doc 18 §3.7, as SQL to copy into a module migration (17A §6.3, completed by
19A K-01). `RlsMatrixIntegrationTest` proves every row of the matrix below against this exact
text, so a policy that departs from it is a decision to write down, not a habit.

Every operational table has: `ENABLE` and `FORCE ROW LEVEL SECURITY`; `own_read` and
`own_write`; `fed_view`; `ext_view`. A table with a counterparty (a document: seller and
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

CREATE POLICY own_write ON <schema>.<table> FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

-- PARTY, on tables with a counterparty column only: the seller reads the buyer's side of the
-- document and the buyer the seller's. What a PARTY caller may see of the row is decided by
-- the masking view it reads through (trading.v_document_party, M4: no cost, margin or internal
-- notes), never by this policy, which decides which rows. OWN is admitted here too, so a
-- caller in OWN scope reads its documents through the same view. The location line keeps a
-- shop-scoped user to its shop's documents (doc 18: a shop sees nothing of a sibling shop);
-- 19A section 1 leaves it out (CR-17A-3).
CREATE POLICY party_read ON <schema>.<table> FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity()
                OR counterparty_entity_id = kernel.scope_entity())
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

-- FEDERATION_VIEW: everything, read only.
CREATE POLICY fed_view ON <schema>.<table> FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

-- EXTERNAL_TIMEBOXED: a regulator or auditor reads the entities of its grant, read only.
CREATE POLICY ext_view ON <schema>.<table> FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));
```

What the matrix guarantees, and the test proves:

| Class | Reads | Writes |
|---|---|---|
| OWN, entity-wide | rows owned by the scope entity, and rows where it is the counterparty | rows owned by the scope entity |
| OWN, at a location | the entity's rows at that location | the same |
| PARTY | rows where the scope entity is owner or counterparty | nothing |
| FEDERATION_VIEW | every row | nothing |
| EXTERNAL_TIMEBOXED | rows owned by a granted entity; nothing with an empty grant | nothing |
| NONE, or a transaction that forgot the scope | nothing | nothing |

Three cases the template does not cover, and what does:

- **Reference data and projections** (a permission catalogue, a directory maintained by a
  trigger) belong to no tenant. They get a read policy for the classes that may read them and
  `seed_reference`/`projection_write` policies **`TO app_seed`** for the writer (kernel `V0005`;
  the migrator is a member): a role, not a variable.
- **The Federation acting on rows it does not own** (registering an entity, activating it):
  the guard in the handler decides (`FederationCaller` in M1 is the example), and the
  `federation_*` policies of `party.entity` carry it in SQL. That is the fifth class
  `CR-21A-1` item 2 asks 17A to adopt.
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
