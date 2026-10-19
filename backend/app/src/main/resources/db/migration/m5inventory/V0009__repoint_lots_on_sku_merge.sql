-- M5-08 / CAT-03: Lot re-pointing on SKU merge (doc 25 section 3.1; 22A section 6).
--
-- On sku.merged.v1, lots of the merged local SKU re-point to the target canonical SKU.
-- Grants UPDATE on stock_lot.sku_id and permits the Federation or the lot owner to update the lot's SKU.

GRANT UPDATE (sku_id) ON inventory.stock_lot TO app_rw;

CREATE POLICY fed_merge_lot ON inventory.stock_lot FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_entity() = (SELECT kernel.system_entity())
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));


