-- M1 / K-03
-- Narrow SECURITY DEFINER function used by the kernel permission resolver.
-- It exposes only a boolean authorization fact and does not bypass module
-- boundaries by returning security rows.

CREATE OR REPLACE FUNCTION security.user_has_permission(
    p_user_id uuid,
    p_entity_id uuid,
    p_location_id uuid,
    p_permission_code text
)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM security.app_user u
        JOIN security.user_role ur
          ON ur.user_id = u.user_id
        JOIN security.role r
          ON r.role_id = ur.role_id
        JOIN security.role_permission rp
          ON rp.role_id = r.role_id
        WHERE u.user_id = p_user_id
          AND u.status <> 'DEACTIVATED'
          AND ur.scope_entity_id = p_entity_id
          AND (
                (
                    p_location_id IS NULL
                    AND ur.scope_location_id IS NULL
                )
                OR
                (
                    p_location_id IS NOT NULL
                    AND (
                        ur.scope_location_id IS NULL
                        OR ur.scope_location_id = p_location_id
                    )
                )
          )
          AND r.status = 'ACTIVE'
          AND rp.permission_code = p_permission_code
    );
$$;

REVOKE ALL
    ON FUNCTION security.user_has_permission(uuid, uuid, uuid, text)
    FROM PUBLIC;

GRANT EXECUTE
    ON FUNCTION security.user_has_permission(uuid, uuid, uuid, text)
    TO app_rw;
