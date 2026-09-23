CREATE OR REPLACE FUNCTION security.user_belongs_to_entity(
    p_user_id uuid,
    p_entity_id uuid
)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, kernel
SET row_security = off
AS $$
BEGIN
    -- Entity governance actions are never location-scoped.
    IF kernel.scope_class() <> 'OWN'
       OR kernel.scope_location() IS NOT NULL THEN
        RETURN false;
    END IF;

    -- The caller must either be acting for the target entity itself,
    -- or be the Federation acting across entities.
    IF kernel.scope_entity() <> p_entity_id
       AND NOT EXISTS (
            SELECT 1
            FROM party.federation_identity fi
            WHERE fi.entity_id = kernel.scope_entity()
       ) THEN
        RETURN false;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM security.app_user u
        WHERE u.user_id = p_user_id
          AND u.home_entity_id = p_entity_id
    );
END;
$$;

REVOKE ALL
    ON FUNCTION security.user_belongs_to_entity(uuid, uuid)
    FROM PUBLIC;

GRANT EXECUTE
    ON FUNCTION security.user_belongs_to_entity(uuid, uuid)
    TO app_rw;