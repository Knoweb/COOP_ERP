-- M5 Inventory: Stock Lot Repricing & Valuation Adjustments, and Waste/Spoilage Write-Off Categories (PGL-M2-03).
-- 1. Updates write_off category constraint to include 'WASTE' and 'SPOILAGE'.
-- 2. Creates insert-only ledger table inventory.stock_repricing with standard four-class RLS policies.
-- 3. Creates SECURITY DEFINER function inventory.apply_lot_reprice for authorized stock lot revaluations.
--    Direct arbitrary UPDATE on unit_cost remains refused to app_rw (enforcing the core invariant:
--    "a lot keeps its identity and its cost and is never removed"). Revaluation is permitted
--    only through this audited procedure, requiring an active OWN scope, an authenticated user,
--    and a corresponding immutable stock_repricing ledger record written in the current transaction.

-- 1. Extend write_off categories
ALTER TABLE inventory.write_off DROP CONSTRAINT IF EXISTS write_off_category_check;
ALTER TABLE inventory.write_off ADD CONSTRAINT write_off_category_check
    CHECK (category IN (
        'DAMAGED_IN_TRANSIT', 'DAMAGED_IN_STORE', 'EXPIRED',
        'THEFT', 'SHRINKAGE_UNEXPLAINED', 'STAFF_CONSUMPTION', 'SAMPLES', 'DONATION', 'OTHER',
        'WASTE', 'SPOILAGE'
    ));

-- 2. Repricing audit and traceability ledger (insert-only)
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

-- 3. Authorized stock lot revaluation via SECURITY DEFINER function.
-- Ensure app_rw holds no direct UPDATE privilege on stock_lot.unit_cost:
DO $$
BEGIN
    EXECUTE 'REVOKE UPDATE (unit_cost) ON inventory.stock_lot FROM app_rw';
EXCEPTION WHEN OTHERS THEN
    NULL;
END
$$;

DROP POLICY IF EXISTS lot_reprice_update ON inventory.stock_lot;
CREATE POLICY lot_reprice_update ON inventory.stock_lot FOR UPDATE TO app_seed
    USING (true) WITH CHECK (true);
GRANT SELECT, UPDATE (unit_cost) ON inventory.stock_lot TO app_seed;

CREATE OR REPLACE FUNCTION inventory.apply_lot_reprice(
    p_repricing_id  uuid,
    p_stock_lot_id  uuid,
    p_new_unit_cost numeric
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, inventory, pg_temp
AS $$
DECLARE
    v_lot       inventory.stock_lot%ROWTYPE;
    v_repricing inventory.stock_repricing%ROWTYPE;
BEGIN
    -- 1. Class gate: must run in an active OWN scope
    IF kernel.scope_class() IS DISTINCT FROM 'OWN' THEN
        RAISE EXCEPTION 'm5.scope.own_required' USING ERRCODE = 'insufficient_privilege';
    END IF;

    -- 2. Authenticated user gate: must have an active user setting
    IF nullif(current_setting('app.user_id', true), '') IS NULL THEN
        RAISE EXCEPTION 'm5.reprice.user_required' USING ERRCODE = 'insufficient_privilege';
    END IF;

    -- 3. Invariant gate: cost must be non-negative
    IF p_new_unit_cost IS NULL OR p_new_unit_cost < 0 THEN
        RAISE EXCEPTION 'm5.reprice.invalid_cost' USING ERRCODE = 'data_exception';
    END IF;

    -- 4. Audit linkage gate: requires a corresponding immutable stock_repricing ledger row
    -- created within the current transaction (xmin = pg_current_xact_id()::xid).
    SELECT * INTO v_repricing
      FROM inventory.stock_repricing
     WHERE repricing_id = p_repricing_id
       AND (stock_lot_id = p_stock_lot_id OR target_lot_id = p_stock_lot_id)
       AND xmin = pg_current_xact_id()::xid;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'm5.reprice.audit_required' USING ERRCODE = 'insufficient_privilege';
    END IF;

    -- 5. Scope & tenant isolation: caller must own the repricing record and the lot
    IF v_repricing.owner_entity_id IS DISTINCT FROM kernel.scope_entity() THEN
        RAISE EXCEPTION 'm5.reprice.not_found' USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF kernel.scope_location() IS NOT NULL AND v_repricing.location_id IS DISTINCT FROM kernel.scope_location() THEN
        RAISE EXCEPTION 'm5.location.not_in_scope' USING ERRCODE = 'insufficient_privilege';
    END IF;

    SELECT * INTO v_lot
      FROM inventory.stock_lot
     WHERE stock_lot_id = p_stock_lot_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'm5.reprice.not_found' USING ERRCODE = 'data_exception';
    END IF;

    IF v_lot.owner_entity_id IS DISTINCT FROM kernel.scope_entity() THEN
        RAISE EXCEPTION 'm5.reprice.not_found' USING ERRCODE = 'insufficient_privilege';
    END IF;

    IF kernel.scope_location() IS NOT NULL AND v_lot.location_id IS DISTINCT FROM kernel.scope_location() THEN
        RAISE EXCEPTION 'm5.location.not_in_scope' USING ERRCODE = 'insufficient_privilege';
    END IF;

    -- 6. Apply the revalued unit cost (unit_cost only; qty_on_hand is never touched here)
    UPDATE inventory.stock_lot
       SET unit_cost = p_new_unit_cost
     WHERE stock_lot_id = p_stock_lot_id;

    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION inventory.apply_lot_reprice(uuid, uuid, numeric) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION inventory.apply_lot_reprice(uuid, uuid, numeric) TO app_rw;
