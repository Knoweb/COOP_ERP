-- Two decisions taken on 27 September 2026 on the architect's delegation (docs/PROGRESS.md,
-- Deviations; CR-21A-1 item 2).
--
-- 1. The database can name the Federation. Until now the entity the platform acts as lived in
--    a Spring property only (coop-erp.system.entity-id: SystemScope, FederationCaller,
--    M2SeedLoader), so no policy or trigger could ask "is this row the Federation's", and
--    22A section 3's shared_read on catalogue.sku trusted the status column alone.
--    kernel.system_identity holds one row, written on every start from that property by
--    SystemEntityRecorder as the migrator (a member of app_seed); kernel.system_entity()
--    reads it. The property stays the one source: the table is its copy for SQL, never
--    edited by hand and never by the application user. Without the property the row is left
--    as it was; with no row at all the function returns NULL, and a policy comparing with it
--    admits nothing (it fails closed).
--
-- 2. A shop-scoped session reads the documents of its shop only (doc 18 section 3.7: OWN reads
--    "rows where owner = scope entity (and location when the assignment is location-scoped)";
--    M-05). V0055 admitted location_id IS NULL in own_read and own_update of kernel.document
--    as well as of kernel.numbering_series, so a shop saw the entity's location-less documents
--    (orders, discrepancies, adjustments raised entity-wide). The series keep that admission:
--    a document at a shop may draw its number from an ENTITY series (24B: ORD, DISC, ADJ ...),
--    and a counter carries no business content. The documents lose it, and own_write gains the
--    location line, so a shop-scoped session can create only documents at its own location,
--    never one it could not read back.

-- ---- 1. the system entity -------------------------------------------------------------------

CREATE TABLE kernel.system_identity (
    singleton   boolean     PRIMARY KEY DEFAULT true CHECK (singleton),
    entity_id   uuid        NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE kernel.system_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.system_identity FORCE ROW LEVEL SECURITY;

-- The Federation's id is not a secret (it is in every snapshot of a SHARED item); every class
-- reads it, so that a policy may call kernel.system_entity() in any scope. Only the start-up
-- recorder writes it, as the migrator, through app_seed.
CREATE POLICY reference_read ON kernel.system_identity
    FOR SELECT TO app_rw USING (true);
CREATE POLICY seed_reference ON kernel.system_identity
    FOR ALL TO app_seed USING (true) WITH CHECK (true);

REVOKE ALL PRIVILEGES ON kernel.system_identity FROM PUBLIC;
GRANT SELECT ON kernel.system_identity TO app_rw;
GRANT SELECT, INSERT, UPDATE ON kernel.system_identity TO app_seed;

-- The entity the platform acts as: the Federation. Call it as (SELECT kernel.system_entity())
-- inside a policy, so PostgreSQL evaluates it once per statement, not once per row.
CREATE FUNCTION kernel.system_entity()
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    SELECT entity_id FROM kernel.system_identity WHERE singleton
$$;

-- ---- 2. documents at a shop -----------------------------------------------------------------

ALTER POLICY own_read ON kernel.document
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

ALTER POLICY own_update ON kernel.document
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

ALTER POLICY own_write ON kernel.document
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
