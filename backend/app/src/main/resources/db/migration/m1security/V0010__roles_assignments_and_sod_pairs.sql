-- M1-08: roles, assignments and separation-of-duties pairs (21A sections 5 and 6).
--
-- 1. The three deletes M1 performs, each by a command handler that audits it and publishes
--    an event:
--      security.user_role        RevokeRole ("insert / delete user_role (the one DELETE M1
--                                performs; audited)", 21A section 6);
--      security.role_permission  AmendRole ("insert / replace role_permission set");
--      security.sod_pair         an entity removes a pair it added itself (its own row only;
--                                a federation default, owner NULL, is never deleted).
--    None of the three is a document, a ledger or an audit row (AGENTS.md); each is a current
--    fact whose history is the audit trail. SchemaRulesIntegrationTest names exactly these
--    three as the tables app_rw may delete from.
--
-- 2. The Federation authors the role templates (owner NULL, is_template true): 21A section 6.1,
--    "r.ownerEntityId().equals(ctx.entityId()) || (r.isTemplate() && ctx.isFederation())".
--    V0001 admits only the caller's own entity, so a template could be written by the seed
--    loader alone and a template could never change, which leaves the drift diff of doc 19
--    DR-4 nothing to show. The federation_template_* policies below are the fifth class of
--    RLS_POLICY_TEMPLATE.md ("the Federation acting on rows it does not own").
--
-- 3. security.role_assignment_count: whether a role still has assignments, for RetireRole.
--    A template may be assigned directly in any entity, and the Federation cannot read another
--    entity's user_role rows; the function answers one number and nothing else.

GRANT DELETE ON security.user_role, security.role_permission, security.sod_pair TO app_rw;

-- The same rows own_write lets the caller insert: a location-scoped caller revokes at its
-- location only.
CREATE POLICY own_delete ON security.user_role
    FOR DELETE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND scope_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR scope_location_id = kernel.scope_location()
        )
    );

-- Roles are authored entity-wide: a location-scoped session changes no role.
CREATE POLICY own_delete ON security.role_permission
    FOR DELETE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND kernel.scope_location() IS NULL
        AND EXISTS (
            SELECT 1
              FROM security.role r
             WHERE r.role_id = role_permission.role_id
               AND r.owner_entity_id = kernel.scope_entity()
        )
    );

CREATE POLICY own_delete ON security.sod_pair
    FOR DELETE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND kernel.scope_location() IS NULL
        AND owner_entity_id = kernel.scope_entity()
    );


-- Whether the session is the Federation acting entity-wide in the OWN class. It reads
-- party.federation_identity under the caller's own policies, which show the row to that
-- session alone (m1party V0002), so it trusts no session variable beyond the kernel's four.
CREATE OR REPLACE FUNCTION security.scope_is_federation()
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    SELECT kernel.scope_class() = 'OWN'
       AND kernel.scope_location() IS NULL
       AND EXISTS (
           SELECT 1
             FROM party.federation_identity fi
            WHERE fi.entity_id = kernel.scope_entity()
       );
$$;

REVOKE ALL ON FUNCTION security.scope_is_federation() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION security.scope_is_federation() TO app_rw;


CREATE POLICY federation_template_insert ON security.role
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id IS NULL
        AND is_template
        AND security.scope_is_federation()
    );

CREATE POLICY federation_template_update ON security.role
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id IS NULL
        AND is_template
        AND security.scope_is_federation()
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id IS NULL
        AND is_template
        AND security.scope_is_federation()
    );

CREATE POLICY federation_template_insert ON security.role_permission
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND security.scope_is_federation()
        AND EXISTS (
            SELECT 1
              FROM security.role r
             WHERE r.role_id = role_permission.role_id
               AND r.owner_entity_id IS NULL
               AND r.is_template
        )
    );

CREATE POLICY federation_template_update ON security.role_permission
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND security.scope_is_federation()
        AND EXISTS (
            SELECT 1
              FROM security.role r
             WHERE r.role_id = role_permission.role_id
               AND r.owner_entity_id IS NULL
               AND r.is_template
        )
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND security.scope_is_federation()
        AND EXISTS (
            SELECT 1
              FROM security.role r
             WHERE r.role_id = role_permission.role_id
               AND r.owner_entity_id IS NULL
               AND r.is_template
        )
    );

CREATE POLICY federation_template_delete ON security.role_permission
    FOR DELETE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND security.scope_is_federation()
        AND EXISTS (
            SELECT 1
              FROM security.role r
             WHERE r.role_id = role_permission.role_id
               AND r.owner_entity_id IS NULL
               AND r.is_template
        )
    );


-- How many assignments a role still has, counting only users who are not DEACTIVATED (their
-- rows are kept for history, 21A section 6, DeactivateUser). Answers only a caller who may
-- manage the role: the owner acting entity-wide, or the Federation for a template; anyone else
-- gets NULL. No row leaves the security schema.
--
-- The function runs as its owner, the migrator. FORCE ROW LEVEL SECURITY binds the owner too,
-- and "SET row_security = off" does not lift that: it makes the query fail with "query would be
-- affected by row-level security policy" instead. So the two tables it counts get a read policy
-- for app_seed, the group the migrator belongs to (kernel V0005): a role, not a session
-- variable, and one no application connection is a member of.
CREATE POLICY definer_read ON security.user_role
    FOR SELECT
    TO app_seed
    USING (true);

CREATE POLICY definer_read ON security.app_user
    FOR SELECT
    TO app_seed
    USING (true);

CREATE OR REPLACE FUNCTION security.role_assignment_count(
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
        AND EXISTS (
            SELECT 1
              FROM party.federation_identity fi
             WHERE fi.entity_id = kernel.scope_entity()
        )
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

REVOKE ALL ON FUNCTION security.role_assignment_count(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION security.role_assignment_count(uuid) TO app_rw;
