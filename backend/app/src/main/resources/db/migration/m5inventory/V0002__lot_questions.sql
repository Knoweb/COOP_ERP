-- M5-04: the two questions M2's guards ask of M5 (22A section 6; m2catalogue.api.InventoryLotQuery),
-- answered from the lots themselves. M2 asks them inside its own command, in its caller's scope,
-- and the answer is about lots that scope may not read:
--   * UpdateSku: "a SKU's base unit and tracking flags cannot change once a lot exists", any
--     entity's lot (the Federation edits a shared SKU that a society holds);
--   * CorrectBatch: "the caller owns a lot of the batch", asked for the caller's entity.
-- Each is a yes or no and nothing else, so a SECURITY DEFINER function answers it without
-- opening a lot to the caller. The functions run as their owner, the migrator (a member of
-- app_seed), whom FORCE ROW LEVEL SECURITY binds too: the policy below lets that role read lots,
-- and no login but the migrator holds it.

CREATE POLICY lot_questions ON inventory.stock_lot FOR SELECT TO app_seed
    USING (true);

CREATE FUNCTION inventory.sku_has_lot(p_sku_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, inventory
AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku_id = p_sku_id);
$$;

CREATE FUNCTION inventory.entity_holds_lot_of(p_batch_id uuid, p_entity_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, inventory
AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_lot WHERE batch_id = p_batch_id AND owner_entity_id = p_entity_id);
$$;

REVOKE ALL ON FUNCTION inventory.sku_has_lot(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION inventory.entity_holds_lot_of(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION inventory.sku_has_lot(uuid) TO app_rw;
GRANT EXECUTE ON FUNCTION inventory.entity_holds_lot_of(uuid, uuid) TO app_rw;
