-- M1-04 Trading relationships (21A section 3.1 and 6; doc 21 section 3.2 and 4.2).
--
-- V0001 created party.entity_relationship with the columns, the two CHECKs and the A-I3
-- exclusion constraint (one ACTIVE row per seller and buyer per date), and the own_read,
-- own_write, own_update and fed_view policies; V0004 added a PARTY-only party_read. What the
-- handlers of M1-04 need beyond that, and nothing else:
--
-- 1. party_read as RLS_POLICY_TEMPLATE.md writes it. The counterparty (the buyer) reads the
--    rows it is a side of in its OWN scope as well as in PARTY: a society's administrator,
--    working in the society's own scope, sees the terms its distributor sells to it on. V0004
--    admitted PARTY only, so in OWN the buyer saw nothing. The table has no location column,
--    so the template's location line is left out; the counterparty may read, never write
--    (own_write and own_update stay the seller's).
--
-- 2. ext_view, the template's fifth class, which V0001 did not give this table: a regulator
--    reads the relationships of the entities of its grant, as seller.
--
-- 3. allocation_rule takes the values 24A section 6.2 names (FCFS the default; PRO_RATA and
--    QUOTA are strategies behind the same M4 interface); V0001 left the column free text.
--
-- 4. The index 21A section 7 names for ListRelationships and LookupRelationship, and one for
--    the buyer side, which the exclusion constraint's index (seller first) does not serve.
--
-- 5. party.trading_standing(entity): the type and status of one entity, for the guards of
--    OpenTradingRelationship ("seller and buyer ACTIVE or ONBOARDING; tier rule"). A seller
--    in OWN scope cannot read the buyer's party.entity row (own_read is its own row only), and
--    must not: the row carries the VAT and registration numbers and the officer. The function
--    returns the two facts the guard needs and nothing else, to an OWN caller only; the same
--    narrow-function pattern as security.entity_has_user_manager (m1security V0003).
--    The function runs as its owner, the migrator, and FORCE ROW LEVEL SECURITY binds the
--    owner too; the migrator is a member of app_seed (kernel V0005), so a read policy TO
--    app_seed lets the function see the row. It is a role, not a session variable, and no
--    application connection is a member of it (RLS_POLICY_TEMPLATE.md, reference data).

-- 1. The counterparty reads its side, in OWN or PARTY.
DROP POLICY party_read ON party.entity_relationship;

CREATE POLICY party_read
    ON party.entity_relationship
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() IN ('OWN', 'PARTY')
        AND (
            owner_entity_id = kernel.scope_entity()
            OR buyer_entity_id = kernel.scope_entity()
        )
    );

-- 2. EXTERNAL_TIMEBOXED reads the relationships its grant covers, read only.
CREATE POLICY ext_view
    ON party.entity_relationship
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (kernel.granted_entities())
    );

-- 3. The allocation rules M4 knows.
ALTER TABLE party.entity_relationship
    ADD CONSTRAINT entity_relationship_allocation_rule_check
    CHECK (allocation_rule IN ('FCFS', 'PRO_RATA', 'QUOTA'));

-- 4. Lookups by pair and date, and by buyer.
CREATE INDEX entity_relationship_pair_from
    ON party.entity_relationship (seller_entity_id, buyer_entity_id, effective_from);

CREATE INDEX entity_relationship_buyer
    ON party.entity_relationship (buyer_entity_id);

-- 5. The two facts about a counterparty that the opening guards need.
CREATE POLICY standing_read
    ON party.entity
    FOR SELECT
    TO app_seed
    USING (true);

CREATE OR REPLACE FUNCTION party.trading_standing(
    p_entity_id uuid
)
RETURNS TABLE (
    entity_type text,
    status text
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, party, kernel
AS $$
    -- Only a caller acting for its own entity opens a relationship; nobody else asks.
    SELECT e.entity_type, e.status
      FROM party.entity e
     WHERE e.entity_id = p_entity_id
       AND kernel.scope_class() = 'OWN'
       AND kernel.scope_entity() IS NOT NULL;
$$;

REVOKE ALL
    ON FUNCTION party.trading_standing(uuid)
    FROM PUBLIC;

GRANT EXECUTE
    ON FUNCTION party.trading_standing(uuid)
    TO app_rw;
