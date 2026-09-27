-- One name for the Federation in SQL (PLAN_TO_M2 task 6.11; CR-21A-1 item 2; decided 27 September
-- 2026 on the architect's delegation, docs/PROGRESS.md Deviations).
--
-- The m1security functions that ask "is the caller the Federation" read party.federation_identity,
-- M1's trigger-kept copy of the FEDERATION row. They now ask kernel.system_entity() (kernel V0061),
-- the copy of coop-erp.system.entity-id that the Java guards read too, so SQL and Java cannot name
-- two different Federations. With no system entity configured it is NULL and every function
-- answers as for a caller that is not the Federation: fail closed. Only the Federation test
-- changes; each body is otherwise the one in force (V0003, V0004, V0010, V0013).
--
-- After this nothing reads party.federation_identity. m1party V0012 replaced the table with a
-- view of the kernel's row, granted to nobody, which stays only because V0010 of this stream
-- names it in a LANGUAGE sql function that a fresh database creates after the whole m1party
-- stream has run.

-- ---- the Federation acting entity-wide (V0010) -----------------------------------------------

CREATE OR REPLACE FUNCTION security.scope_is_federation()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    -- coalesce: a missing scope or no system entity is a plain "no", never NULL.
    SELECT coalesce(kernel.scope_class() = 'OWN'
                    AND kernel.scope_location() IS NULL
                    AND kernel.scope_entity() = kernel.system_entity(), false);
$$;

-- ---- ActivateEntity's prerequisite (V0003) ---------------------------------------------------

CREATE OR REPLACE FUNCTION security.entity_has_user_manager(p_entity_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, kernel
AS $$
BEGIN
    -- Only an entity-wide OWN scope may use this guard.
    IF kernel.scope_class() <> 'OWN'
       OR kernel.scope_location() IS NOT NULL THEN
        RETURN false;
    END IF;

    -- The caller itself must be the Federation.
    IF kernel.scope_entity() IS DISTINCT FROM kernel.system_entity() THEN
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

-- ---- the responsible officer (V0004) ---------------------------------------------------------

CREATE OR REPLACE FUNCTION security.user_belongs_to_entity(p_user_id uuid, p_entity_id uuid)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, kernel
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
       AND kernel.scope_entity() IS DISTINCT FROM kernel.system_entity() THEN
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

-- ---- how many hold a role (V0010) ------------------------------------------------------------

CREATE OR REPLACE FUNCTION security.role_assignment_count(p_role_id uuid)
RETURNS bigint
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, kernel
AS $$
DECLARE
    v_owner uuid;
    v_template boolean;
BEGIN
    IF kernel.scope_class() <> 'OWN'
       OR kernel.scope_location() IS NOT NULL THEN
        RETURN NULL;
    END IF;

    SELECT r.owner_entity_id, r.is_template
      INTO v_owner, v_template
      FROM security.role r
     WHERE r.role_id = p_role_id;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF v_owner IS NOT NULL AND v_owner <> kernel.scope_entity() THEN
        RETURN NULL;
    END IF;

    IF v_owner IS NULL AND NOT (
        v_template
        AND kernel.scope_entity() IS NOT DISTINCT FROM kernel.system_entity()
        AND kernel.system_entity() IS NOT NULL
    ) THEN
        RETURN NULL;
    END IF;

    RETURN (
        SELECT count(*)
          FROM security.user_role ur
          JOIN security.app_user u
            ON u.user_id = ur.user_id
         WHERE ur.role_id = p_role_id
           AND u.status <> 'DEACTIVATED'
    );
END;
$$;

-- ---- a template's holders outside the Federation (V0013) -------------------------------------

CREATE OR REPLACE FUNCTION security.template_assignments_outside_federation(p_role_id uuid)
RETURNS bigint
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, kernel
AS $$
DECLARE
    v_owner uuid;
    v_template boolean;
    v_federation uuid;
BEGIN
    IF kernel.scope_class() <> 'OWN'
       OR kernel.scope_location() IS NOT NULL THEN
        RETURN NULL;
    END IF;

    v_federation := kernel.system_entity();

    IF v_federation IS NULL OR kernel.scope_entity() IS DISTINCT FROM v_federation THEN
        RETURN NULL;
    END IF;

    SELECT r.owner_entity_id, r.is_template
      INTO v_owner, v_template
      FROM security.role r
     WHERE r.role_id = p_role_id;

    IF NOT FOUND OR v_owner IS NOT NULL OR NOT v_template THEN
        RETURN NULL;
    END IF;

    RETURN (
        SELECT count(*)
          FROM security.user_role ur
          JOIN security.app_user u
            ON u.user_id = ur.user_id
         WHERE ur.role_id = p_role_id
           AND ur.scope_entity_id <> v_federation
           AND u.status <> 'DEACTIVATED'
    );
END;
$$;
