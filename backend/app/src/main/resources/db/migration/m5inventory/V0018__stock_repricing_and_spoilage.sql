-- M5 Inventory: Stock Lot Repricing & Valuation Adjustments, and Waste/Spoilage Write-Off Categories (PGL-M2-03).
-- 1. Updates write_off category constraint to include 'WASTE' and 'SPOILAGE'.
-- 2. Grants UPDATE (unit_cost) on inventory.stock_lot to app_rw.
-- 3. Creates insert-only ledger table inventory.stock_repricing with standard four-class RLS policies.

-- 1. Extend write_off categories
ALTER TABLE inventory.write_off DROP CONSTRAINT IF EXISTS write_off_category_check;
ALTER TABLE inventory.write_off ADD CONSTRAINT write_off_category_check
    CHECK (category IN (
        'DAMAGED_IN_TRANSIT', 'DAMAGED_IN_STORE', 'EXPIRED',
        'THEFT', 'SHRINKAGE_UNEXPLAINED', 'STAFF_CONSUMPTION', 'SAMPLES', 'DONATION', 'OTHER',
        'WASTE', 'SPOILAGE'
    ));

-- 2. Permit app_rw to update unit_cost on stock_lot for authorized repricing
GRANT UPDATE (unit_cost) ON inventory.stock_lot TO app_rw;

-- 3. Repricing audit and traceability ledger (insert-only)
CREATE TABLE inventory.stock_repricing (
    repricing_id        uuid          PRIMARY KEY,
    owner_entity_id     uuid          NOT NULL,
    location_id         uuid          NOT NULL,
    stock_lot_id        uuid          NOT NULL,
    sku_id              uuid          NOT NULL,
    batch_id            uuid          NOT NULL,
    condition           text          NOT NULL CHECK (condition IN ('GOOD', 'DAMAGED')),
    qty_affected        numeric(14,3) NOT NULL CHECK (qty_affected > 0),
    previous_unit_cost  numeric(14,4) NOT NULL CHECK (previous_unit_cost >= 0),
    new_unit_cost       numeric(14,4) NOT NULL CHECK (new_unit_cost >= 0),
    target_condition    text          NOT NULL CHECK (target_condition IN ('GOOD', 'DAMAGED')),
    target_lot_id       uuid,
    value_adjustment    numeric(14,2) NOT NULL,
    reason              text          NOT NULL,
    repriced_by         uuid          NOT NULL,
    repriced_at         timestamptz   NOT NULL
);

CREATE INDEX repricing_by_location ON inventory.stock_repricing (location_id, repriced_at DESC);
CREATE INDEX repricing_by_lot ON inventory.stock_repricing (stock_lot_id);
CREATE INDEX repricing_by_sku ON inventory.stock_repricing (sku_id);

ALTER TABLE inventory.stock_repricing ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_repricing FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.stock_repricing FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON inventory.stock_repricing FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON inventory.stock_repricing FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON inventory.stock_repricing FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.stock_repricing TO app_rw;

