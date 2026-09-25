-- M1-07 users and credentials.
--
-- 1. A user name is unique across the federation (21A section 3.2: username UNIQUE; doc 21
--    section 4.4: "unique username in federation"), but an administrator sees only the users
--    of its own entity, so CreateUser cannot ask the table itself. This function answers the
--    one fact, a boolean, and nothing about whose name it is. Only an OWN caller may ask, so a
--    viewer or an unscoped session cannot enumerate names. The unique constraint stays the
--    backstop.
--
--    A SECURITY DEFINER function runs as the migrator, who owns the tables; under FORCE ROW
--    LEVEL SECURITY the owner is subject to the policies, and "SET row_security = off" does not
--    lift them: it makes every query the policies would touch fail ("query would be affected by
--    row-level security policy"). So the definer is admitted the way the seed path is (kernel
--    V0005, m1security V0006): a read policy TO app_seed, the migrator's group. The
--    application connects as coop_app, which is not in that group, and sees nothing more.
CREATE POLICY definer_read ON security.app_user
    FOR SELECT TO app_seed USING (true);

CREATE POLICY definer_read ON security.user_role
    FOR SELECT TO app_seed USING (true);

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
        WHERE lower(u.username) = lower(p_username)
    );
END;
$$;

REVOKE ALL ON FUNCTION security.username_taken(text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION security.username_taken(text) TO app_rw;

-- The two guard functions of M1-03 (V0003, V0004) carry the same "SET row_security = off" and
-- so fail the same way the first time they read a user; their integration tests stub them.
-- With the policies above they read as intended once the setting is removed. ActivateEntity
-- and AppointResponsibleOfficer are what this repairs; their own guards are unchanged.
ALTER FUNCTION security.entity_has_user_manager(uuid) RESET row_security;

ALTER FUNCTION security.user_belongs_to_entity(uuid, uuid) RESET row_security;


-- 2. A session scoped to a shop reads the users that work there, not its sibling shops'
--    operators (doc 18 section 3.7 and the RLS matrix of 21A section 9: "shop-scoped user vs
--    sibling shop operators"). app_user has no location column: where a user works is its
--    assignments. So a location-scoped OWN session sees the users of its entity that hold an
--    assignment at that location or an entity-wide one (an entity-wide assignment applies at
--    every location, m1security V0008); an entity-wide session sees every user of its entity,
--    as before. The permission resolver reads app_user in the caller's own scope, and a user
--    it needs is always one with such an assignment, so nothing it resolves changes.
--    The same rule bounds an UPDATE; an INSERT keeps the entity test only, because a new user
--    has no assignment yet (and user commands run in an entity-wide scope, M1-07).
--    A merged migration is never edited: the V0001 policies are altered here.
ALTER POLICY own_read ON security.app_user
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR EXISTS (
                SELECT 1
                FROM security.user_role ur
                WHERE ur.user_id = app_user.user_id
                  AND ur.scope_entity_id = kernel.scope_entity()
                  AND (ur.scope_location_id IS NULL
                       OR ur.scope_location_id = kernel.scope_location())
            )
        )
    );

ALTER POLICY own_update ON security.app_user
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR EXISTS (
                SELECT 1
                FROM security.user_role ur
                WHERE ur.user_id = app_user.user_id
                  AND ur.scope_entity_id = kernel.scope_entity()
                  AND (ur.scope_location_id IS NULL
                       OR ur.scope_location_id = kernel.scope_location())
            )
        )
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );
