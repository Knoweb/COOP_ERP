-- M8 Reporting: Asset Register & Branch / Coop City Asset View (Polgolla M1-04).
-- Maintain society-owned fixed/non-stock assets (land, buildings, vehicles, equipment, furniture).
CREATE TABLE reporting.asset (
    asset_id         uuid          PRIMARY KEY,
    owner_entity_id  uuid          NOT NULL,
    asset_code       varchar(32)   NOT NULL,
    name             text          NOT NULL,
    description      text,
    asset_type       text          NOT NULL CHECK (asset_type IN ('LAND', 'BUILDING', 'VEHICLE', 'EQUIPMENT', 'FURNITURE', 'OTHER')),
    location_id      uuid,
    acquisition_date date,
    acquisition_cost numeric(14,2) CHECK (acquisition_cost IS NULL OR acquisition_cost >= 0),
    status           text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISPOSED', 'MAINTENANCE', 'INACTIVE')),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       uuid,
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    updated_by       uuid,
    CONSTRAINT asset_owner_code_uq UNIQUE (owner_entity_id, asset_code)
);

CREATE INDEX asset_by_owner_loc ON reporting.asset (owner_entity_id, location_id);
CREATE INDEX asset_by_owner_type ON reporting.asset (owner_entity_id, asset_type);

ALTER TABLE reporting.asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.asset FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.asset FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON reporting.asset FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_update ON reporting.asset FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON reporting.asset FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON reporting.asset FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.asset TO app_rw;
GRANT UPDATE (name, description, asset_type, location_id, acquisition_date, acquisition_cost, status, updated_at, updated_by)
    ON reporting.asset TO app_rw;
