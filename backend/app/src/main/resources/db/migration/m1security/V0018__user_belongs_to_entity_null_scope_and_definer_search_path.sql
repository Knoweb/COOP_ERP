-- Wave 2 of the code review (RLS-16, RLS-13), decided 6 October 2026 on the architect's delegation:
-- docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md (1), (2).

-- ---- 1. a NULL scope entity answers "no" (RLS-16) -------------------------------------------------

-- V0016's test `kernel.scope_entity() <> p_entity_id AND ...` is NULL for a NULL scope entity, so
-- the IF was skipped and the function answered for any entity. The scope customizer never produces
-- an OWN scope without an entity, and the function must not depend on that: every comparison is
-- IS DISTINCT FROM, a NULL scope entity is refused outright, and the answer is coalesced to false,
-- as security.scope_is_federation does in V0016. Otherwise the body of V0016.
CREATE OR REPLACE FUNCTION security.user_belongs_to_entity(p_user_id uuid, p_entity_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, kernel, pg_temp
AS $$
BEGIN
    -- Entity governance actions are never location-scoped.
    IF kernel.scope_class() IS DISTINCT FROM 'OWN'
       OR kernel.scope_location() IS NOT NULL
       OR kernel.scope_entity() IS NULL
       OR p_entity_id IS NULL THEN
        RETURN false;
    END IF;

    -- The caller must either be acting for the target entity itself,
    -- or be the Federation acting across entities.
    IF kernel.scope_entity() IS DISTINCT FROM p_entity_id
       AND kernel.scope_entity() IS DISTINCT FROM kernel.system_entity() THEN
        RETURN false;
    END IF;

    RETURN coalesce(EXISTS (
        SELECT 1
        FROM security.app_user u
        WHERE u.user_id = p_user_id
          AND u.home_entity_id = p_entity_id
    ), false);
END;
$$;

-- ---- 2. definer hygiene (RLS-13) ------------------------------------------------------------------

-- pg_temp last on every SECURITY DEFINER function of the schema (kernel V0086 says why); bodies
-- unchanged. security.appointed_officer_name (V0017) already ends in pg_temp.
ALTER FUNCTION security.entity_has_user_manager(uuid)
    SET search_path = pg_catalog, security, party, kernel, pg_temp;
ALTER FUNCTION security.role_assignment_count(uuid)
    SET search_path = pg_catalog, security, party, kernel, pg_temp;
ALTER FUNCTION security.template_assignments_outside_federation(uuid)
    SET search_path = pg_catalog, security, party, kernel, pg_temp;
ALTER FUNCTION security.user_permissions_at(uuid, uuid, uuid)
    SET search_path = pg_catalog, security, kernel, pg_temp;
ALTER FUNCTION security.username_taken(text)
    SET search_path = pg_catalog, security, kernel, pg_temp;
ALTER FUNCTION security.user_has_any_assignment(uuid)
    SET search_path = pg_catalog, security, pg_temp;
