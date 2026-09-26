-- Review fixes of M1-07 and M1-08 (26 September 2026). A merged migration is never edited.
--
-- 1. security.user_permissions_at: what a user holds at an entity through every ACTIVE role
--    assigned there, entity-wide or at any of its locations, leaving one role out (the one
--    being changed). AssignRole's per-person ROLE-mode check (doc 19 section 3.2: "a pair in
--    ROLE mode forbids one person holding both permissions at all") read the user's
--    assignments under the caller's own policy, and own_read (V0008) shows a location-scoped
--    caller its own location's rows and the entity-wide ones, never the rows of another
--    location. So a shop-1 manager could give a person the second half of a pair they already
--    held at shop 2. This function answers the codes, as security.role_assignment_count
--    answers a number: a SECURITY DEFINER read that no row leaves. It answers only an OWN
--    caller of that entity (entity-wide or at one of its locations); anyone else gets nothing.
--    The definer reads user_role and app_user under definer_read (V0010) and role and
--    role_permission under seed_reference (V0006), both TO app_seed, the migrator's group.
--
-- 2. security.template_assignments_outside_federation: how many assignments of a template
--    belong to users outside the Federation. AmendRole on a template ran none of the guards
--    on the people who hold it, although a template is assigned directly in every entity
--    (M1-08): dropping gov.user.manage from Entity Administrator would take every society's
--    last user manager away in one commit, and adding a FEDERATION-scope code would give it to
--    every society administrator. AmendRole refuses those two changes while this count is
--    above zero. Answers the Federation for a template, as role_assignment_count does.
--
-- 3. security.username_taken compared lower(u.username), which the unique index on username
--    cannot serve, so every CreateUser scanned security.app_user. CreateUser stores the name
--    in lower case (CreateUserHandler), so the column is compared as stored and the index
--    serves the lookup. The body is otherwise V0012's.

CREATE OR REPLACE FUNCTION security.user_permissions_at(
    p_user_id uuid,
    p_entity_id uuid,
    p_leaving_out_role_id uuid
)
RETURNS SETOF text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, kernel
AS $$
BEGIN
    IF kernel.scope_class() <> 'OWN'
       OR kernel.scope_entity() IS DISTINCT FROM p_entity_id THEN
        RETURN;
    END IF;

    RETURN QUERY
        SELECT DISTINCT rp.permission_code::text
          FROM security.user_role ur
          JOIN security.role r
            ON r.role_id = ur.role_id
           AND r.status = 'ACTIVE'
          JOIN security.role_permission rp
            ON rp.role_id = r.role_id
         WHERE ur.user_id = p_user_id
           AND ur.scope_entity_id = p_entity_id
           AND (p_leaving_out_role_id IS NULL OR ur.role_id <> p_leaving_out_role_id);
END;
$$;

REVOKE ALL ON FUNCTION security.user_permissions_at(uuid, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION security.user_permissions_at(uuid, uuid, uuid) TO app_rw;


CREATE OR REPLACE FUNCTION security.template_assignments_outside_federation(
    p_role_id uuid
)
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

    SELECT fi.entity_id
      INTO v_federation
      FROM party.federation_identity fi
     WHERE fi.entity_id = kernel.scope_entity();

    IF NOT FOUND THEN
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

REVOKE ALL ON FUNCTION security.template_assignments_outside_federation(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION security.template_assignments_outside_federation(uuid) TO app_rw;


CREATE OR REPLACE FUNCTION security.username_taken(p_username text)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, kernel
AS $$
BEGIN
    IF kernel.scope_class() <> 'OWN' THEN
        RETURN false;
    END IF;

    RETURN EXISTS (
        SELECT 1
        FROM security.app_user u
        WHERE u.username = lower(p_username)
    );
END;
$$;
