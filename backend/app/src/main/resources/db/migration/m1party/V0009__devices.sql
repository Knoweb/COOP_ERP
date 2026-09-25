-- M1-06 devices (21A section 3.1, section 6; doc 21 sections 3.4 and 4.5).
--
-- V0001 created party.device with the columns of 21A and the OWN and FEDERATION_VIEW
-- policies. What the device handlers need beyond that, and nothing else:
--
-- 1. A device is enrolled at a location (the shop it is shipped to, or the warehouse or office
--    a driver's handset or a workstation belongs to). 21A gives the device no location, so its
--    policies could not keep a shop-scoped user to the devices of that shop, as doc 18 section
--    3.7 asks of every table (a shop sees nothing of a sibling shop). The column is NOT NULL:
--    no handler wrote a device before this migration, so no row lacks it.
--
-- 2. The policies take the location line of the template (RLS_POLICY_TEMPLATE.md) on read,
--    insert and update, as party.till_position already has them; ext_view is added from the
--    template, read only.
--
-- 3. Only an ACTIVE or a SUSPENDED device holds a position: an ENROLLED device has not been
--    assigned yet and a RETIRED one has given its position up (21A: "retire requires
--    unassigned"). A SUSPENDED device keeps its position until a replacement is assigned there,
--    so a device that turns up again is reinstated into its own lane (doc 21 section 4.5).

-- 1. the location of the device
ALTER TABLE party.device
    ADD COLUMN location_id uuid NOT NULL
        REFERENCES party.location (location_id);

CREATE INDEX device_location_idx
    ON party.device (owner_entity_id, location_id);

-- 2. the template's location line
DROP POLICY own_read ON party.device;
DROP POLICY own_write ON party.device;
DROP POLICY own_update ON party.device;

CREATE POLICY own_read
    ON party.device
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR location_id = kernel.scope_location()
        )
    );

CREATE POLICY own_write
    ON party.device
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR location_id = kernel.scope_location()
        )
    );

CREATE POLICY own_update
    ON party.device
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR location_id = kernel.scope_location()
        )
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR location_id = kernel.scope_location()
        )
    );

CREATE POLICY ext_view
    ON party.device
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (kernel.granted_entities())
    );

-- 3. who may hold a position
ALTER TABLE party.device
    ADD CONSTRAINT device_position_holder_ck
        CHECK (current_till_position_id IS NULL OR status IN ('ACTIVE', 'SUSPENDED'));
