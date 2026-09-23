-- Review of M1-03 (#72), 23 September 2026. Three things V0002 and V0003 left open.
--
-- 1. party.entity_party_directory let every PARTY-class caller read the legal name of every
--    entity in the system: its policy tested the scope class and nothing else. A counterparty
--    may see the names of the entities it trades with, and its own (doc 18 §3.7: PARTY is
--    "trading documents where the caller's entity is seller or buyer").
--
-- 2. To say "trades with", a PARTY caller must be able to read its own rows of
--    party.entity_relationship, and V0001 gave that table OWN and FEDERATION_VIEW policies
--    only. This is the PARTY policy of 17A §6.3 applied to its first table; 19A K-01 owns the
--    template and generalises it.
--
-- 3. Both projection tables were left without FORCE ROW LEVEL SECURITY, because their
--    SECURITY DEFINER triggers run as the migrator (the owner) and FORCE binds the owner too.
--    The migrator is a member of app_seed (kernel V0005), the group that writes rows that
--    belong to no tenant; a policy TO app_seed admits the triggers and FORCE goes on, so the
--    schema rules test holds for every table again.

-- 2. PARTY reads the relationships it is a side of.
CREATE POLICY party_read
    ON party.entity_relationship
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'PARTY'
        AND (seller_entity_id = kernel.scope_entity() OR buyer_entity_id = kernel.scope_entity())
    );

-- 1. The directory shows a PARTY caller its own entity and its active counterparties.
DROP POLICY party_names_read ON party.entity_party_directory;

CREATE POLICY party_names_read
    ON party.entity_party_directory
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'PARTY'
        AND (
            entity_id = kernel.scope_entity()
            OR entity_id IN (
                SELECT r.seller_entity_id FROM party.entity_relationship r
                 WHERE r.buyer_entity_id = kernel.scope_entity() AND r.status = 'ACTIVE'
                UNION
                SELECT r.buyer_entity_id FROM party.entity_relationship r
                 WHERE r.seller_entity_id = kernel.scope_entity() AND r.status = 'ACTIVE'
            )
        )
    );

-- 3. The triggers write as the migrator, a member of app_seed; then FORCE.
CREATE POLICY projection_write
    ON party.entity_party_directory
    FOR ALL TO app_seed USING (true) WITH CHECK (true);

CREATE POLICY projection_write
    ON party.federation_identity
    FOR ALL TO app_seed USING (true) WITH CHECK (true);

ALTER TABLE party.entity_party_directory FORCE ROW LEVEL SECURITY;
ALTER TABLE party.federation_identity    FORCE ROW LEVEL SECURITY;
