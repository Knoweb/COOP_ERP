-- M1-03
-- PARTY-safe entity-name projection.
--
-- GetEntity / ListEntities expose full entity facts through the normal
-- party.entity RLS path for OWN / FEDERATION_VIEW.
--
-- PARTY callers may see names only. Giving PARTY SELECT access directly to
-- party.entity would expose VAT, registration, officer and governance fields,
-- because PostgreSQL RLS filters rows, not columns.
--
-- This small projection table therefore contains only the fields PARTY may
-- receive. Application code has SELECT only; a trigger owned by the schema
-- keeps it synchronized with party.entity.

CREATE TABLE party.entity_party_directory (
    entity_id uuid PRIMARY KEY
        REFERENCES party.entity(entity_id),

    legal_name_en text NOT NULL,
    legal_name_si text,
    legal_name_ta text
);


ALTER TABLE party.entity_party_directory
    ENABLE ROW LEVEL SECURITY;

-- Deliberately not FORCE RLS.
-- The SECURITY DEFINER synchronization trigger must be able to maintain the
-- projection while app_rw remains read-only.
CREATE POLICY party_names_read
    ON party.entity_party_directory
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'PARTY'
    );


REVOKE INSERT, UPDATE, DELETE
    ON party.entity_party_directory
    FROM app_rw;

GRANT SELECT
    ON party.entity_party_directory
    TO app_rw;


-- Backfill rows already present when this migration is installed.
INSERT INTO party.entity_party_directory (
    entity_id,
    legal_name_en,
    legal_name_si,
    legal_name_ta
)
SELECT
    entity_id,
    legal_name_en,
    legal_name_si,
    legal_name_ta
FROM party.entity
ON CONFLICT (entity_id)
DO UPDATE
SET
    legal_name_en = EXCLUDED.legal_name_en,
    legal_name_si = EXCLUDED.legal_name_si,
    legal_name_ta = EXCLUDED.legal_name_ta;


CREATE OR REPLACE FUNCTION party.sync_entity_party_directory()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, party
AS $$
BEGIN
    INSERT INTO party.entity_party_directory (
        entity_id,
        legal_name_en,
        legal_name_si,
        legal_name_ta
    )
    VALUES (
        NEW.entity_id,
        NEW.legal_name_en,
        NEW.legal_name_si,
        NEW.legal_name_ta
    )
    ON CONFLICT (entity_id)
    DO UPDATE
    SET
        legal_name_en = EXCLUDED.legal_name_en,
        legal_name_si = EXCLUDED.legal_name_si,
        legal_name_ta = EXCLUDED.legal_name_ta;

    RETURN NEW;
END;
$$;


REVOKE ALL
    ON FUNCTION party.sync_entity_party_directory()
    FROM PUBLIC;


DROP TRIGGER IF EXISTS trg_sync_entity_party_directory
    ON party.entity;

CREATE TRIGGER trg_sync_entity_party_directory
AFTER INSERT OR UPDATE OF
    legal_name_en,
    legal_name_si,
    legal_name_ta
ON party.entity
FOR EACH ROW
EXECUTE FUNCTION party.sync_entity_party_directory();