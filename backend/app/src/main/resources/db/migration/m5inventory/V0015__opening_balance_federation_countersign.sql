-- INV-09 (M5-10b, M5-19): retain the batch data that the entity signed, register the
-- batch only when the Federation countersigns, and let the Federation administer that
-- countersignature from its own entity-wide scope.

ALTER TABLE inventory.opening_balance_line
    ALTER COLUMN batch_id DROP NOT NULL,
    ADD COLUMN batch_no varchar(40),
    ADD COLUMN expiry_date date,
    ADD COLUMN printed_mrp numeric(14,2) CHECK (printed_mrp > 0);

-- Resolving a signed line to the batch registered at countersign is the only permitted
-- change to a line. The quantities, cost and counted batch attributes remain immutable.
CREATE POLICY own_resolve_batch ON inventory.opening_balance_line FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND kernel.scope_location() IS NULL)
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND kernel.scope_location() IS NULL);

GRANT UPDATE (batch_id) ON inventory.opening_balance_line TO app_rw;

CREATE FUNCTION inventory.guard_opening_balance_batch_resolution()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, inventory, pg_temp
AS $function$
BEGIN
    IF OLD.batch_id IS NOT NULL OR NEW.batch_id IS NULL THEN
        RAISE EXCEPTION 'opening balance batch can only be resolved once';
    END IF;
    IF NOT EXISTS (
        SELECT 1
          FROM inventory.opening_balance b
         WHERE b.opening_balance_id = OLD.opening_balance_id
           AND b.status = 'SIGNED_ENTITY'
    ) THEN
        RAISE EXCEPTION 'opening balance batch can only be resolved after entity signature';
    END IF;
    RETURN NEW;
END;
$function$;

CREATE TRIGGER opening_balance_line_resolve_batch
BEFORE UPDATE OF batch_id ON inventory.opening_balance_line
FOR EACH ROW
EXECUTE FUNCTION inventory.guard_opening_balance_batch_resolution();

-- This is the RLS template's fed_admin exception: only the configured Federation, in an
-- entity-wide OWN scope, can discover and lock an MPCS balance before validating its lifecycle
-- state. PostgreSQL applies UPDATE policies to SELECT FOR UPDATE. WITH CHECK retains ordinary
-- ownership, so the policy permits the cross-owner lock but never a cross-owner mutation: the
-- eventual state transition must run under the derived owner scope. Business authorization and
-- MFA remain handler responsibilities.
CREATE POLICY federation_countersign_read ON inventory.opening_balance FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_location() IS NULL
           AND kernel.scope_entity() = (SELECT kernel.system_entity()));

CREATE POLICY federation_countersign_update ON inventory.opening_balance FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_location() IS NULL
           AND kernel.scope_entity() = (SELECT kernel.system_entity()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_location() IS NULL
                AND kernel.scope_entity() = (SELECT kernel.system_entity())
                AND owner_entity_id = kernel.scope_entity());

CREATE POLICY federation_countersign_read ON inventory.opening_balance_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_location() IS NULL
           AND kernel.scope_entity() = (SELECT kernel.system_entity()));
