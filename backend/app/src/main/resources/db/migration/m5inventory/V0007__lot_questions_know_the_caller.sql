-- Wave 2 of the code review (RLS-12, RLS-13; RLS-07's lot question), decided 6 October 2026 on the
-- architect's delegation: docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md (1)-(3).

-- ---- 1. the two questions of V0002 answer an OWN caller only (RLS-12) -------------------------------

-- V0002's entity_holds_lot_of(batch, entity) took the entity from the caller, so any app_rw session,
-- in any class, learned whether any entity holds any batch. The pattern of RLS_POLICY_TEMPLATE.md
-- ("a function that answers across tenants"): the caller is kernel.scope_entity(), never a
-- parameter; the parameter names the thing asked about; the class is tested first; the answer is
-- one boolean. sku_has_lot keeps its shape (a SKU-wide yes or no, which the Federation editing a
-- SHARED item needs) and gains the class test.
CREATE OR REPLACE FUNCTION inventory.sku_has_lot(p_sku_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, inventory, pg_temp
AS $$
    SELECT coalesce(kernel.scope_class() = 'OWN'
                    AND EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku_id = p_sku_id),
                    false);
$$;

CREATE FUNCTION inventory.caller_holds_lot_of(p_batch_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, inventory, pg_temp
AS $$
    SELECT coalesce(kernel.scope_class() = 'OWN'
                    AND kernel.scope_entity() IS NOT NULL
                    AND EXISTS (SELECT 1
                                  FROM inventory.stock_lot
                                 WHERE batch_id = p_batch_id
                                   AND owner_entity_id = kernel.scope_entity()),
                    false);
$$;

REVOKE ALL ON FUNCTION inventory.caller_holds_lot_of(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION inventory.caller_holds_lot_of(uuid) TO app_rw;

-- The two-argument form stays, executable by its owner (the migrator) only: M2's correction trigger
-- catalogue.batch_apply_correction (m2catalogue V0008), a SECURITY DEFINER function of the migrator,
-- asks it whether the caller holds a lot of the batch it corrects. It is part of M5's contract
-- (m5inventory/README.md) under the decision's one recorded exception to the layering rule for SQL;
-- nothing else may call it.
REVOKE EXECUTE ON FUNCTION inventory.entity_holds_lot_of(uuid, uuid) FROM app_rw;

-- ---- 2. definer hygiene (RLS-13) ------------------------------------------------------------------

-- pg_temp last on every SECURITY DEFINER function of the schema (kernel V0086 says why); bodies
-- unchanged.
ALTER FUNCTION inventory.entity_holds_lot_of(uuid, uuid)
    SET search_path = pg_catalog, inventory, pg_temp;
ALTER FUNCTION inventory.secure_movement_partition(text)
    SET search_path = pg_catalog, inventory, pg_temp;
ALTER FUNCTION inventory.ensure_movement_partitions(integer)
    SET search_path = pg_catalog, inventory, pg_temp;
