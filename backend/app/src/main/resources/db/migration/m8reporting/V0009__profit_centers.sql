-- M8 Reporting: profit centers and agency dimensions (Polgolla M1-08).
-- Dimension table for packing operations, agencies, retail and other profit centers.
CREATE TABLE reporting.profit_center (
    profit_center_id uuid          PRIMARY KEY,
    owner_entity_id  uuid          NOT NULL,
    code             varchar(32)   NOT NULL,
    name             text          NOT NULL,
    category         text          NOT NULL CHECK (category IN ('AGENCY', 'PACKING', 'RETAIL', 'GENERAL')),
    location_id      uuid,
    status           text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    updated_by       uuid,
    CONSTRAINT profit_center_owner_code_uq UNIQUE (owner_entity_id, code)
);

CREATE INDEX profit_center_by_owner_loc ON reporting.profit_center (owner_entity_id, location_id);

ALTER TABLE reporting.profit_center ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.profit_center FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.profit_center FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON reporting.profit_center FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_update ON reporting.profit_center FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON reporting.profit_center FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON reporting.profit_center FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.profit_center TO app_rw;
GRANT UPDATE (name, category, location_id, status, updated_at, updated_by)
    ON reporting.profit_center TO app_rw;
