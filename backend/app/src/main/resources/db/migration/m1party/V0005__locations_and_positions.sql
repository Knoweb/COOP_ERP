-- M1-05 locations and till positions (21A section 3.1, section 6).
--
-- V0001 created party.location and party.till_position with the columns of 21A and the OWN
-- and FEDERATION_VIEW policies. What the handlers of M1-05 need beyond that, and nothing else:
--
-- 1. A position belongs to the entity that owns its shop. V0001 gives till_position its own
--    owner_entity_id (the policies need it) and a plain foreign key to the location, so a row
--    could name one entity as owner and a shop of another. The composite key makes the pair
--    one fact.
--
-- 2. The primary till of a location is one of that location's positions (21A SetPrimaryTill:
--    "position belongs to location"). V0001 left primary_till_position_id without a key; the
--    composite key makes a primary till at another shop impossible, whatever the handler does.
--    MATCH SIMPLE: a location without a primary till (null) is not checked.
--
-- 3. ext_view on both tables, from the policy template (RLS_POLICY_TEMPLATE.md): a regulator
--    with a grant on an entity reads its shops and tills, read only. V0001 had no ext_view on
--    any party table; the others follow with their tickets.

-- 1. owner of a position = owner of its location
ALTER TABLE party.location
    ADD CONSTRAINT location_id_owner_uq UNIQUE (location_id, owner_entity_id);

ALTER TABLE party.till_position
    ADD CONSTRAINT till_position_location_owner_fk
        FOREIGN KEY (location_id, owner_entity_id)
        REFERENCES party.location (location_id, owner_entity_id);

-- 2. the primary till is a position of the same location
ALTER TABLE party.till_position
    ADD CONSTRAINT till_position_id_location_uq UNIQUE (till_position_id, location_id);

ALTER TABLE party.location
    ADD CONSTRAINT location_primary_till_fk
        FOREIGN KEY (primary_till_position_id, location_id)
        REFERENCES party.till_position (till_position_id, location_id);

-- 3. EXTERNAL_TIMEBOXED reads the locations and positions of its granted entities
CREATE POLICY ext_view
    ON party.location
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (kernel.granted_entities())
    );

CREATE POLICY ext_view
    ON party.till_position
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (kernel.granted_entities())
    );
