-- Code review wave 3, M1M2M3M5-26 (on PR #314, TILL-DATA-01): a Federation template amendment
-- reaches every society's tills.
--
-- M1's change-log producer (M1ChangeLogFanOut.onRoleChanged) must find the shops whose users hold
-- the role that changed, so each of their tills gets an urgent operator row. It runs in the event
-- owner's OWN scope, entity-wide. For an entity's own role that is enough. For a Federation
-- template it is not: the Federation authors the template, every society assigns it to its own
-- users in its own scope (21A section 6.1), and security.user_role's own_read admits only the
-- caller's entity. The Federation saw no society's assignment, so an amended template (a refund
-- or price-override permission removed) reached no society till.
--
-- 1. security.role_holder_shops(role): the (user, entity, shop) triples of a role's holders, for
--    a caller who may manage the role, as security.role_assignment_count (V0010, V0016) decides
--    it: OWN, entity-wide, and either the role's owner (its holders inside the owner) or the
--    Federation (kernel.system_entity()) for a template (its holders in every entity). Any other
--    caller, NONE, FEDERATION_VIEW and EXTERNAL_TIMEBOXED included, gets no row. It returns ids
--    only, and only of SHOP locations: what the producer appends to the change log.
--
-- 2. definer_read on party.location TO app_seed: the function runs as its owner, the migrator,
--    whom FORCE ROW LEVEL SECURITY binds too; app_seed is the migrator's group (kernel V0005),
--    a role no application connection is a member of, as security.user_role's definer_read
--    (V0010) lets the same function read the assignments.
--
-- Numbered V0021: M1's two streams share their numbers (m1party ends at V0015, m1security at
-- V0020 on main and on PR #314's branch), so V0021 is free in both.

CREATE POLICY definer_read ON party.location
    FOR SELECT
    TO app_seed
    USING (true);

CREATE FUNCTION security.role_holder_shops(p_role_id uuid)
RETURNS TABLE (holder_user_id uuid, shop_entity_id uuid, shop_location_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, party, kernel, pg_temp
AS $$
    SELECT DISTINCT ur.user_id, l.owner_entity_id, l.location_id
      FROM security.role r
      JOIN security.user_role ur
        ON ur.role_id = r.role_id
      JOIN party.location l
        ON l.owner_entity_id = ur.scope_entity_id
       AND l.location_type = 'SHOP'
       AND (ur.scope_location_id IS NULL OR ur.scope_location_id = l.location_id)
     WHERE r.role_id = p_role_id
       AND kernel.scope_class() = 'OWN'
       AND kernel.scope_location() IS NULL
       AND kernel.scope_entity() IS NOT NULL
       AND (
            -- an entity's own role: its holders inside the entity
            (r.owner_entity_id = kernel.scope_entity()
             AND ur.scope_entity_id = r.owner_entity_id)
            -- a Federation template, asked by the Federation: its holders in every entity
            OR (r.owner_entity_id IS NULL
                AND r.is_template
                AND kernel.system_entity() IS NOT NULL
                AND kernel.scope_entity() = kernel.system_entity())
       )
$$;

REVOKE ALL ON FUNCTION security.role_holder_shops(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION security.role_holder_shops(uuid) TO app_rw;
