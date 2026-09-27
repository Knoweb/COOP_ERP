-- Only the Federation's SKUs are SHARED (22A section 3; doc 22, the SKU table: "Owner is the
-- Federation for SHARED"). Decided 27 September 2026 on the architect's delegation, once
-- kernel V0061 gave SQL a name for the Federation (kernel.system_entity(), the copy of
-- coop-erp.system.entity-id; docs/PROGRESS.md, Deviations, "The Federation is unknown to the
-- database").
--
-- V0001's shared_read trusted the status column: an entity that set SHARED on its own row
-- would have published it federation-wide, and the children that follow a SHARED parent
-- (conversions, barcodes, tags, images: their shared_read asks catalogue.sku under the
-- reader's own policies) with it. Both halves now ask for the owner:
--
--   shared_read   a SHARED row is read by everyone only when the Federation owns it;
--   own_write,    a row can be written SHARED only by its owner being the Federation, so a
--   own_update    society's SHARED row cannot exist at all.
--
-- FederationCaller (M2's handler guard, #120) stays: it gives the caller a message id, the
-- policy is the backstop that holds for every path. With no system entity recorded the
-- function returns NULL and nothing is SHARED to anyone (fail closed).

ALTER POLICY shared_read ON catalogue.sku
    USING (kernel.scope_class() <> 'NONE'
           AND status = 'SHARED'
           AND owner_entity_id = (SELECT kernel.system_entity()));

ALTER POLICY own_write ON catalogue.sku
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (status <> 'SHARED' OR owner_entity_id = (SELECT kernel.system_entity())));

ALTER POLICY own_update ON catalogue.sku
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (status <> 'SHARED' OR owner_entity_id = (SELECT kernel.system_entity())));
