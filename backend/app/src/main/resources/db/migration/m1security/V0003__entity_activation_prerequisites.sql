-- M1-03
-- Narrow privileged guard used by ActivateEntity.
--
-- Federation entity administration must verify that the target entity has at
-- least one user holding gov.user.manage.
--
-- Federation OWN scope must NOT receive general read access to another
-- entity's security rows. This function therefore returns one boolean only.

CREATE OR REPLACE FUNCTION security.entity_has_user_manager(
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
    -- Only an entity-wide OWN scope may use this guard.
    IF kernel.scope_class() <> 'OWN'
       OR kernel.scope_location() IS NOT NULL THEN
        RETURN false;
    END IF;

    -- The caller itself must be the Federation.
    IF NOT EXISTS (
        SELECT 1
        FROM party.federation_identity fi
        WHERE fi.entity_id = kernel.scope_entity()
    ) THEN
        RETURN false;
    END IF;

    -- Return only the readiness fact. No user / role details leave security.
    RETURN EXISTS (
        SELECT 1
        FROM security.app_user u
        JOIN security.user_role ur
          ON ur.user_id = u.user_id
        JOIN security.role r
          ON r.role_id = ur.role_id
        JOIN security.role_permission rp
          ON rp.role_id = r.role_id
        WHERE u.home_entity_id = p_entity_id
          AND u.status <> 'DEACTIVATED'
          AND ur.scope_entity_id = p_entity_id
          AND ur.scope_location_id IS NULL
          AND r.status = 'ACTIVE'
          AND rp.permission_code = 'gov.user.manage'
    );
END;
$$;

REVOKE ALL
    ON FUNCTION security.entity_has_user_manager(uuid)
    FROM PUBLIC;

GRANT EXECUTE
    ON FUNCTION security.entity_has_user_manager(uuid)
    TO app_rw;
