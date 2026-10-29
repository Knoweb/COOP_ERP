-- M5 Inventory: Reorder Level, Reorder Quantity, Maximum Quantity & Low-Stock Controls (PGL-M2-01).
-- Allows entities to configure inventory reorder thresholds per location and SKU.
-- RLS follows the standard four-class policy template (17A §6.3).

CREATE TABLE inventory.reorder_control (
    reorder_control_id  uuid          PRIMARY KEY,
    owner_entity_id     uuid          NOT NULL,
    location_id         uuid          NOT NULL,
    sku_id              uuid          NOT NULL,
    reorder_level       numeric(14,3) NOT NULL CHECK (reorder_level >= 0),
    reorder_qty         numeric(14,3) NOT NULL CHECK (reorder_qty > 0),
    max_qty             numeric(14,3) CHECK (max_qty IS NULL OR (max_qty > 0 AND max_qty >= reorder_level)),
    alert_triggered     boolean       NOT NULL DEFAULT false,
    last_alerted_at     timestamptz,
    last_alert_qty      numeric(14,3),
    status              text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_at          timestamptz   NOT NULL DEFAULT clock_timestamp(),
    updated_at          timestamptz   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT reorder_control_unique UNIQUE (owner_entity_id, location_id, sku_id)
);

CREATE INDEX reorder_control_loc_sku ON inventory.reorder_control (location_id, sku_id) WHERE status = 'ACTIVE';
CREATE INDEX reorder_control_owner ON inventory.reorder_control (owner_entity_id);

ALTER TABLE inventory.reorder_control ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.reorder_control FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.reorder_control FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON inventory.reorder_control FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_update ON inventory.reorder_control FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON inventory.reorder_control FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON inventory.reorder_control FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT, UPDATE ON inventory.reorder_control TO app_rw;
