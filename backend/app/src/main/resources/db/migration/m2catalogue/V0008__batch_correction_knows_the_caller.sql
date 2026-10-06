-- Wave 2 of the code review (RLS-07, RLS-13), decided 6 October 2026 on the architect's delegation:
-- docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md (1), (3).

-- ---- 1. the correction trigger refuses a caller that may not correct the batch (RLS-07) -----------

-- V0004's trigger asked only "REGISTERED, same SKU", never who the caller is: any society's own
-- correction row (own_write admits it) superseded another entity's batch and re-pointed its identity
-- for everyone. The rule of 22A section 6 and doc 22 B-I8 is "the Federation or a lot holder", and
-- it lived in CorrectBatchHandler alone. The trigger runs in the caller's session (the scope settings
-- are visible) as the migrator, so it tests the caller before its two updates: an OWN class, and the
-- caller is the corrected batch's owner, or the Federation (kernel.system_entity(), kernel V0061), or
-- holds a lot of the batch. The lot question is M5's: inventory.entity_holds_lot_of(batch, entity)
-- (m5inventory V0002), the function M5 created for M2's question, executable by the migrator only
-- since m5inventory V0007. A module's migration calling a function another module created for it,
-- read-only, never a table and never a write, is the one recorded exception to the layering rule for
-- SQL (decision (3)); M5 lists the function in its README as part of its contract. A batch is global,
-- so there is no location test: a lot holder in a location-scoped session still corrects. The Java
-- guard stays (m2.batch.not_holder); this is the database holding the same rule for every path.
CREATE OR REPLACE FUNCTION catalogue.batch_apply_correction()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, catalogue, pg_temp
AS $$
DECLARE
    corrected_owner uuid;
    caller uuid := kernel.scope_entity();
BEGIN
    SELECT owner_entity_id INTO corrected_owner
      FROM catalogue.batch
     WHERE batch_id = NEW.corrects_batch_id
       AND sku_id = NEW.sku_id
       AND status = 'REGISTERED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'batch % corrects batch %, which is not a registered batch of the same item',
            NEW.batch_id, NEW.corrects_batch_id;
    END IF;

    IF kernel.scope_class() IS DISTINCT FROM 'OWN'
       OR caller IS NULL
       OR NOT (corrected_owner = caller
               OR caller = (SELECT kernel.system_entity())
               OR inventory.entity_holds_lot_of(NEW.corrects_batch_id, caller)) THEN
        RAISE EXCEPTION 'm2.batch.not_holder' USING ERRCODE = 'insufficient_privilege';
    END IF;

    UPDATE catalogue.batch
       SET status = 'SUPERSEDED'
     WHERE batch_id = NEW.corrects_batch_id
       AND sku_id = NEW.sku_id
       AND status = 'REGISTERED';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'batch % corrects batch %, which is not a registered batch of the same item',
            NEW.batch_id, NEW.corrects_batch_id;
    END IF;

    UPDATE catalogue.batch_key SET batch_id = NEW.batch_id WHERE batch_id = NEW.corrects_batch_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'batch % corrects batch %, which has no identity row', NEW.batch_id, NEW.corrects_batch_id;
    END IF;

    RETURN NULL;
END;
$$;

-- ---- 2. definer hygiene (RLS-13) ------------------------------------------------------------------

-- pg_temp last on every SECURITY DEFINER function of the schema (kernel V0086 says why); bodies
-- unchanged.
ALTER FUNCTION catalogue.ensure_batch_partitions(integer)
    SET search_path = pg_catalog, catalogue, pg_temp;
ALTER FUNCTION catalogue.secure_batch_partition(text)
    SET search_path = pg_catalog, catalogue, pg_temp;
