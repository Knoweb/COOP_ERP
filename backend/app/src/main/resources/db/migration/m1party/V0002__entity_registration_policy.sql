-- M1-03
-- Federation-scoped entity administration.
--
-- Normal OWN scope can only read/write its own entity row.
-- Federation administration is the intentional exception required for:
--   RegisterEntity
--   ActivateEntity
--   SuspendEntity
--   ReinstateEntity
--
-- party.entity cannot determine whether the current OWN scope belongs to the
-- Federation by querying party.entity from its own RLS policy, because that
-- would recurse into the same policy.
--
-- federation_identity therefore stores only the single Federation entity id.
-- It is internal reference data:
--   - app_rw can SELECT it only from the matching Federation OWN scope
--   - app_rw cannot INSERT / UPDATE / DELETE it
--   - the SECURITY DEFINER trigger below maintains it

CREATE TABLE party.federation_identity (
    singleton_key boolean PRIMARY KEY
        DEFAULT true
        CHECK (singleton_key),

    entity_id uuid NOT NULL UNIQUE
        REFERENCES party.entity(entity_id)
);


-- RLS is enabled, but deliberately NOT forced.
--
-- The SECURITY DEFINER trigger executes as the table/function owner and must
-- be able to maintain this internal helper row.
--
-- Application code still cannot write because app_rw receives SELECT only.
ALTER TABLE party.federation_identity
    ENABLE ROW LEVEL SECURITY;


-- Only the actual Federation acting with an entity-wide OWN scope can see
-- the helper row.
CREATE POLICY federation_scope_read
    ON party.federation_identity
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND kernel.scope_location() IS NULL
        AND entity_id = kernel.scope_entity()
    );


-- app_rw must never maintain this table directly.
REVOKE INSERT, UPDATE, DELETE
    ON party.federation_identity
    FROM app_rw;

GRANT SELECT
    ON party.federation_identity
    TO app_rw;


-- If the Federation entity already exists when this migration runs,
-- capture it immediately.
--
-- The existing one-Federation database constraint guarantees that this
-- SELECT can produce at most one row.
INSERT INTO party.federation_identity (
    singleton_key,
    entity_id
)
SELECT
    true,
    entity_id
FROM party.entity
WHERE entity_type = 'FEDERATION'
ON CONFLICT (singleton_key)
DO UPDATE
SET entity_id = EXCLUDED.entity_id;


-- If bootstrap/test data creates the Federation after this migration,
-- capture its id automatically.
--
-- SECURITY DEFINER is intentional:
-- app_rw is not granted write access to federation_identity.
CREATE OR REPLACE FUNCTION party.capture_federation_identity()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, party
AS $$
BEGIN
    IF NEW.entity_type = 'FEDERATION' THEN
        INSERT INTO party.federation_identity (
            singleton_key,
            entity_id
        )
        VALUES (
            true,
            NEW.entity_id
        )
        ON CONFLICT (singleton_key)
        DO UPDATE
        SET entity_id = EXCLUDED.entity_id;
    END IF;

    RETURN NEW;
END;
$$;


-- Do not allow callers to invoke this internal trigger function directly.
REVOKE ALL
    ON FUNCTION party.capture_federation_identity()
    FROM PUBLIC;


DROP TRIGGER IF EXISTS trg_capture_federation_identity
    ON party.entity;

CREATE TRIGGER trg_capture_federation_identity
AFTER INSERT
ON party.entity
FOR EACH ROW
EXECUTE FUNCTION party.capture_federation_identity();


-- -------------------------------------------------------------------------
-- Federation administration policies on party.entity
-- -------------------------------------------------------------------------

-- A normal OWN scope still sees its own party.entity row through the
-- existing own_read policy.
--
-- This additional policy allows the Federation's entity-wide OWN scope
-- to read downstream entities for governance operations.
CREATE POLICY federation_admin_read
    ON party.entity
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND kernel.scope_location() IS NULL
        AND EXISTS (
            SELECT 1
            FROM party.federation_identity fi
            WHERE fi.entity_id = kernel.scope_entity()
        )
    );


-- Federation may create downstream legal entities.
--
-- Creating another FEDERATION is deliberately excluded here.
-- The handler also rejects it, and the database's existing one-Federation
-- uniqueness constraint remains the final backstop.
CREATE POLICY federation_register
    ON party.entity
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND kernel.scope_location() IS NULL

        AND entity_type IN (
            'DISTRIBUTOR',
            'MPCS'
        )

        AND EXISTS (
            SELECT 1
            FROM party.federation_identity fi
            WHERE fi.entity_id = kernel.scope_entity()
        )
    );


-- Federation may update downstream entity reference records for governed
-- lifecycle operations such as:
--   ONBOARDING -> ACTIVE
--   ACTIVE -> SUSPENDED
--   SUSPENDED -> ACTIVE
-- and responsible-officer updates.
--
-- Business-state transitions and permissions remain handler responsibilities;
-- RLS enforces only the row scope.
CREATE POLICY federation_admin_update
    ON party.entity
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND kernel.scope_location() IS NULL

        AND EXISTS (
            SELECT 1
            FROM party.federation_identity fi
            WHERE fi.entity_id = kernel.scope_entity()
        )
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND kernel.scope_location() IS NULL

        AND EXISTS (
            SELECT 1
            FROM party.federation_identity fi
            WHERE fi.entity_id = kernel.scope_entity()
        )
    );