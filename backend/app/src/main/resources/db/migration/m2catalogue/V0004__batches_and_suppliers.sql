-- M2-05: batches and suppliers (22A sections 3 and 6; doc 22 sections 3.7 and 3.9).
--
--   1. catalogue.supplier, the minimal supplier table of 22A section 3 (doc 22 DR-2, CR-22-1),
--      which M2-01 deferred to this ticket. M10 absorbs it in phase 2.
--   2. CorrectBatch by a caller that is not the registering entity (22A section 6: "caller owns
--      a lot of the batch (M5 query) or F"). own_update on batch and on batch_key admits the
--      registering entity only, so the old batch could not be marked SUPERSEDED nor its identity
--      re-pointed by a lot holder or the Federation. The correction row is inserted by the caller
--      as before (own_write); an AFTER INSERT trigger running as the function's owner then marks
--      the corrected batch SUPERSEDED and re-points its identity row. Who may correct is the
--      handler's guard (the Federation, or a lot holder through InventoryLotQuery, which answers
--      "no lots" until M5 exists): the database cannot ask M5.

-- ---------------------------------------------------------------------------------------------
-- 1. Suppliers
CREATE TABLE catalogue.supplier (
    supplier_id     uuid        PRIMARY KEY,
    owner_entity_id uuid        NOT NULL,
    name            text        NOT NULL,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_entity_id, name)
);

ALTER TABLE catalogue.supplier ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.supplier FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.supplier FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON catalogue.supplier FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON catalogue.supplier FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON catalogue.supplier FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
-- A batch is global identity and carries its supplier (doc 22 section 3.7, B-03): a society
-- receiving the Federation's batch reads the batch's supplier like the batch. Supplier names are
-- business names, not personal data (doc 22 section 9.3). The class test keeps a transaction with
-- no scope out, as on batch.
CREATE POLICY authenticated_read ON catalogue.supplier FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');

-- RegisterSupplier inserts; no operation of 22A changes or removes a supplier.
GRANT SELECT, INSERT ON catalogue.supplier TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 2. The correction of a batch
--
-- The BEFORE INSERT trigger of V0003 keeps the identity of a first registration only; the
-- correction branch moves to the AFTER INSERT trigger below.
CREATE OR REPLACE FUNCTION catalogue.batch_keep_one_identity()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, catalogue
AS $$
BEGIN
    IF NEW.corrects_batch_id IS NULL THEN
        INSERT INTO catalogue.batch_key (sku_id, supplier_id, batch_no, batch_id, owner_entity_id)
        VALUES (NEW.sku_id, NEW.supplier_id, NEW.batch_no, NEW.batch_id, NEW.owner_entity_id);
    END IF;
    RETURN NEW;
END;
$$;

-- Runs as its owner (the migrator, a member of app_seed), after the replacement row is in place:
-- the corrected batch must be a REGISTERED batch of the same SKU, which becomes SUPERSEDED, and
-- its identity row now names the replacement. A trigger function cannot be called on its own,
-- so the only way into it is an insert of a correction row, which own_write admits in the
-- caller's own OWN scope only.
CREATE FUNCTION catalogue.batch_apply_correction()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, catalogue
AS $$
BEGIN
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

REVOKE ALL ON FUNCTION catalogue.batch_apply_correction() FROM PUBLIC;

CREATE TRIGGER batch_correction
    AFTER INSERT ON catalogue.batch
    FOR EACH ROW WHEN (NEW.corrects_batch_id IS NOT NULL)
    EXECUTE FUNCTION catalogue.batch_apply_correction();

-- Under FORCE ROW LEVEL SECURITY the owner is bound by the policies too, and those of batch and
-- batch_key admit app_rw only. These admit the function's owner for exactly the two changes of a
-- correction: a REGISTERED batch becomes SUPERSEDED, an identity row is re-pointed.
CREATE POLICY correction_read ON catalogue.batch FOR SELECT TO app_seed
    USING (true);
CREATE POLICY correction_supersede ON catalogue.batch FOR UPDATE TO app_seed
    USING (status = 'REGISTERED')
    WITH CHECK (status = 'SUPERSEDED');
CREATE POLICY correction_read ON catalogue.batch_key FOR SELECT TO app_seed
    USING (true);
CREATE POLICY correction_repoint ON catalogue.batch_key FOR UPDATE TO app_seed
    USING (true)
    WITH CHECK (true);

-- The partitions carry the parent's policies (secure_batch_partition copies the missing ones by
-- name); the ones created from now on get the two new policies when they are created.
DO $$
DECLARE
    part record;
BEGIN
    FOR part IN
        SELECT c.relname
          FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
         WHERE i.inhparent = 'catalogue.batch'::regclass
    LOOP
        PERFORM catalogue.secure_batch_partition(part.relname);
    END LOOP;
END;
$$;
