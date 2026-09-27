-- Two decisions of 27 September 2026, on the architect's delegation (docs/PROGRESS.md Deviations;
-- PLAN_TO_M2 tasks 6.11 and 6.12).
--
-- 1. One name for the Federation in SQL (6.11; CR-21A-1 item 2). The federation_* policies of
--    party.entity asked party.federation_identity, a copy of the FEDERATION row that a trigger
--    kept, visible only to the Federation's own entity-wide scope. Since kernel V0061 the kernel
--    names the entity the platform acts as, kernel.system_entity(), a copy of
--    coop-erp.system.entity-id written on every start, which is also what the Java guards read
--    (SystemScope, FederationCaller). Two copies could disagree (a second FEDERATION row cannot
--    exist, but the property can name another entity, and then the guards and the policies
--    answered differently); one cannot. The policies now compare the scope entity with it,
--    called as (SELECT kernel.system_entity()) so it is evaluated once per statement. With no
--    system entity configured the function is NULL and the policies admit nothing: fail closed.
--
--    The trigger and the table go here; m1security V0016 moves the functions that read the
--    table to the kernel function. The name is kept as a view with no grant (see below).
--
-- 2. A shop writes only at its own location (6.12). party.location's own_write had no location
--    line: a session scoped to one shop could register a location of its entity, a row it could
--    not then read. Registering a location is entity-wide work (21A section 6, RegisterLocation,
--    prt.location.register); an entity-wide session is unaffected. own_update, and both
--    policies of party.till_position and party.device, have the line already (V0005, V0009).

-- ---- 1. the Federation, from the kernel ------------------------------------------------------

ALTER POLICY federation_admin_read ON party.entity
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_location() IS NULL
           AND kernel.scope_entity() = (SELECT kernel.system_entity()));

ALTER POLICY federation_register ON party.entity
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_location() IS NULL
                AND entity_type IN ('DISTRIBUTOR', 'MPCS')
                AND kernel.scope_entity() = (SELECT kernel.system_entity()));

ALTER POLICY federation_admin_update ON party.entity
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_location() IS NULL
           AND kernel.scope_entity() = (SELECT kernel.system_entity()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_location() IS NULL
                AND kernel.scope_entity() = (SELECT kernel.system_entity()));

DROP TRIGGER IF EXISTS trg_capture_federation_identity ON party.entity;
DROP FUNCTION IF EXISTS party.capture_federation_identity();

-- The copy goes; the name stays, as a view of the kernel's row with no grant to anyone. Nothing
-- reads it once m1security V0016 has run. It exists because, on a fresh database, the m1party
-- stream runs whole before m1security, and m1security V0010 creates a LANGUAGE sql function
-- whose body names party.federation_identity, which PostgreSQL resolves at creation; and the
-- drop cannot move to m1security, whose migrations touch the security schema only (rule R4,
-- tools/check-schema-ownership.mjs). A view of the kernel's row is not a second copy: it cannot
-- disagree with kernel.system_entity().
DROP TABLE party.federation_identity;

CREATE VIEW party.federation_identity WITH (security_invoker = true) AS
    SELECT true AS singleton_key, entity_id
      FROM kernel.system_identity
     WHERE singleton;

REVOKE ALL PRIVILEGES ON party.federation_identity FROM PUBLIC;

-- ---- 2. a location is registered entity-wide -------------------------------------------------

ALTER POLICY own_write ON party.location
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
