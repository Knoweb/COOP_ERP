-- V0019__stock_alerts_and_slow_moving.sql: Expiry and slow-moving alert state tracking (PGL-M2-07).
-- Tracks triggered alerts to guarantee deterministic deduplication across repeated scheduled sweeps
-- and manual user scans.

CREATE TABLE inventory.stock_alert_state (
    alert_id            uuid          PRIMARY KEY,
    owner_entity_id     uuid          NOT NULL,
    location_id         uuid          NOT NULL,
    alert_type          text          NOT NULL CHECK (alert_type IN ('NEAR_EXPIRY', 'SLOW_MOVING')),
    sku_id              uuid          NOT NULL,
    batch_id            uuid,
    alert_tier          text          NOT NULL,
    status              text          NOT NULL DEFAULT 'TRIGGERED' CHECK (status IN ('TRIGGERED', 'RESOLVED')),
    qty_on_hand         numeric(14,3) NOT NULL,
    last_alerted_at     timestamptz   NOT NULL DEFAULT now(),
    resolved_at         timestamptz,
    details             jsonb
);

CREATE UNIQUE INDEX uq_stock_alert_near_expiry ON inventory.stock_alert_state (location_id, batch_id, alert_type)
    WHERE status = 'TRIGGERED' AND batch_id IS NOT NULL;

CREATE UNIQUE INDEX uq_stock_alert_slow_moving ON inventory.stock_alert_state (location_id, sku_id, alert_type)
    WHERE status = 'TRIGGERED' AND alert_type = 'SLOW_MOVING';

CREATE INDEX idx_stock_alert_lookup ON inventory.stock_alert_state (location_id, alert_type, status);

ALTER TABLE inventory.stock_alert_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_alert_state FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.stock_alert_state FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON inventory.stock_alert_state FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_update ON inventory.stock_alert_state FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON inventory.stock_alert_state FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON inventory.stock_alert_state FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT, UPDATE ON inventory.stock_alert_state TO app_rw;
