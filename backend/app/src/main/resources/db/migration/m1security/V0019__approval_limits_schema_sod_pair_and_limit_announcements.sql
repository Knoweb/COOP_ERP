-- Wave 2 of the code review (M1A-04, M1A-05, M1A-06; M5-09, the M1 half), decided 6 October 2026 on
-- the architect's delegation: docs/progress/deviations/2026-10-06-wave2-m1-administration.md (3),
-- (4); 2026-10-06-wave2-stock-approvals.md (1), (2); 2026-10-06-wave2-trading-projections.md (5);
-- CR-21A-7.
--
-- It runs as the migrator, a member of app_seed, which the seed_reference policies of V0006 admit
-- under FORCE ROW LEVEL SECURITY on the catalogue and the pairs. On a new database the first two
-- parts change nothing: the seed loader fills those tables after the migrations, from
-- seed/m1party/permissions.yaml (which now carries the same limits_schema) and
-- seed/m1party/sod-pairs.yaml (which no longer carries the pair).

-- ---- 1. the approval limit of a write-off and of an adjustment (M5-09, doc 25 section 7) ------------

-- "writeoff_bands as role limits (M1)": a grant of these two codes says up to what value its holder
-- approves. Until now no permission carried a limits_schema, M1 refused limits on a permission
-- without one, and the loader never updates an existing row, so no limit could be set at all.
UPDATE security.permission
   SET limits_schema = '{"properties":{"max_value":{"type":"number","minimum":0}},"required":["max_value"]}'::jsonb
 WHERE permission_code IN ('inv.writeoff.approve', 'inv.adjust.approve')
   AND limits_schema IS DISTINCT FROM
       '{"properties":{"max_value":{"type":"number","minimum":0}},"required":["max_value"]}'::jsonb;

-- ---- 2. the pair that is in neither doc 21 nor 21A (M1A-05) ------------------------------------------

-- gov.role.manage / gov.user.manage was added in M1-08; no handler enforces it and the seeded Entity
-- Administrator template holds both codes. Its protection is the code guardrails doc 21 names
-- (within the grantor, the last user manager, the rank rule of ResetCredential). Removed by id;
-- an entity's own row for the same codes, if any, is the entity's and stays.
DELETE FROM security.sod_pair WHERE sod_pair_id = '01921319-dfc4-7264-b580-c11df5ed4c53';

-- A new catalogue version empties every cached permission set (19A section 3), as V0014 does.
INSERT INTO security.permission_catalogue_version (rv, published_at)
VALUES (COALESCE((SELECT max(rv) FROM security.permission_catalogue_version), 0) + 1, now());

-- ---- 3. which pair's credit limit has been announced as an event (M1A-06; M8 D6) --------------------

-- ActivateRelationship now publishes credit_limit.changed.v1 (null to the opening limit), so M8
-- learns a relationship's limit from an event and never reads M1 at query time (28A section 1).
-- The relationships activated before this release never published one; CreditLimitBackfillJob
-- publishes it once for each of them, in the seller's name, and a row here is how it knows a pair
-- is done. ActivateRelationship and an amendment that changes the limit write the row too, so the
-- job never announces a pair twice. One row per pair, owned by the seller; insert-only.
CREATE TABLE security.credit_limit_announcement (
    owner_entity_id uuid NOT NULL,
    buyer_entity_id uuid NOT NULL,
    relationship_id uuid NOT NULL,
    announced_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT credit_limit_announcement_pk PRIMARY KEY (owner_entity_id, buyer_entity_id)
);

ALTER TABLE security.credit_limit_announcement ENABLE ROW LEVEL SECURITY;
ALTER TABLE security.credit_limit_announcement FORCE ROW LEVEL SECURITY;

-- RLS_POLICY_TEMPLATE.md, without the location line (no location_id) and without own_update and
-- own_delete (no UPDATE or DELETE is granted).
CREATE POLICY own_read ON security.credit_limit_announcement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_write ON security.credit_limit_announcement FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity());

CREATE POLICY fed_view ON security.credit_limit_announcement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON security.credit_limit_announcement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON security.credit_limit_announcement TO app_rw;
