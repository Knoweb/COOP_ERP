-- M8 Reporting: shop sales targets (Polgolla M1-01).
-- A manager defines sales targets for a shop/location and a period.
CREATE TABLE reporting.shop_target (
    target_id        uuid          PRIMARY KEY,
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    period_start     date          NOT NULL,
    period_end       date          NOT NULL,
    target_amount    numeric(14,2) NOT NULL,
    currency         text          NOT NULL DEFAULT 'LKR',
    created_at       timestamptz   NOT NULL,
    created_by       uuid,
    updated_at       timestamptz   NOT NULL,
    updated_by       uuid,
    CONSTRAINT shop_target_period_valid CHECK (period_start <= period_end),
    CONSTRAINT shop_target_amount_positive CHECK (target_amount >= 0),
    CONSTRAINT shop_target_shop_period_uq UNIQUE (owner_entity_id, location_id, period_start, period_end)
);

CREATE INDEX shop_target_by_location_period
    ON reporting.shop_target (location_id, period_start, period_end);

ALTER TABLE reporting.shop_target ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.shop_target FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.shop_target FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON reporting.shop_target FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_update ON reporting.shop_target FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON reporting.shop_target FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON reporting.shop_target FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.shop_target TO app_rw;
GRANT UPDATE (target_amount, period_start, period_end, updated_at, updated_by)
    ON reporting.shop_target TO app_rw;
