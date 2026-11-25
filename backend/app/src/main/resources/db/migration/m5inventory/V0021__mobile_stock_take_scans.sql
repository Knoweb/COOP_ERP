-- M5 Inventory: Mobile Stock-Taking Scans Ledger (PGL-M2-09).
-- Insert-only ledger recording individual barcode and SKU scans during a stock-take session.
-- Provides persistent idempotency deduplication by scan_id across network retries and offline queue sync.
-- Follows standard 4-class RLS policies; granted SELECT and INSERT only to app_rw.

CREATE TABLE inventory.stock_take_scan (
    scan_id          uuid          PRIMARY KEY,
    task_id          uuid          NOT NULL REFERENCES inventory.count_task (task_id),
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    scanned_code     text          NOT NULL,
    sku_id           uuid          NOT NULL,
    batch_id         uuid          NOT NULL,
    condition        text          NOT NULL DEFAULT 'GOOD' CHECK (condition IN ('GOOD', 'DAMAGED')),
    counted_qty      numeric(14,3) NOT NULL CHECK (counted_qty > 0),
    scanned_at       timestamptz   NOT NULL,
    scanned_by       uuid,
    device_id        text,
    notes            text,
    created_at       timestamptz   NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX stock_take_scan_task_idx ON inventory.stock_take_scan (task_id);
CREATE INDEX stock_take_scan_task_sku_idx ON inventory.stock_take_scan (task_id, sku_id);
CREATE INDEX stock_take_scan_by_location ON inventory.stock_take_scan (location_id, scanned_at DESC);

ALTER TABLE inventory.stock_take_scan ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_take_scan FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.stock_take_scan FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON inventory.stock_take_scan FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON inventory.stock_take_scan FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON inventory.stock_take_scan FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.stock_take_scan TO app_rw;
