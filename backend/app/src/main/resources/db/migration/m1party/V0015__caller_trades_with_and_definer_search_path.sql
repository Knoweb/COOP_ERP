-- Wave 2 of the code review (RLS-06 and M9-06's door, RLS-13), decided 6 October 2026 on the
-- architect's delegation: docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md (1), (4).

-- ---- 1. party.caller_trades_with(entity): M1 publishes the fact -----------------------------------

-- "May the caller reach this entity?" is a question about the caller's own trading relationships,
-- and M1 owns them. The function follows the pattern of RLS_POLICY_TEMPLATE.md ("a function that
-- answers across tenants"): it answers an OWN caller only, takes the caller from kernel.scope_entity()
-- (the parameter names the entity asked about, never the entity answered for), and returns one
-- boolean. ACTIVE or SUSPENDED in either direction: a suspended buyer still owes and must still be
-- told (M9's notification_recipients calls it, m9integration V0006); a DRAFT or REPLACED relationship
-- is no relationship. The directory party_names_read already shows each side its counterparties, so
-- this is no oracle. SECURITY DEFINER runs as the migrator, a member of app_seed, whom FORCE ROW LEVEL
-- SECURITY binds too; the policy below lets that role read the relationships, as standing_read (V0007)
-- lets it read party.entity.
CREATE POLICY trades_with_read
    ON party.entity_relationship
    FOR SELECT
    TO app_seed
    USING (true);

CREATE FUNCTION party.caller_trades_with(p_entity_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, party, pg_temp
AS $$
    -- coalesce: a missing scope is a plain "no", never NULL.
    SELECT coalesce(
        kernel.scope_class() = 'OWN'
        AND kernel.scope_entity() IS NOT NULL
        AND p_entity_id IS NOT NULL
        AND EXISTS (SELECT 1
                      FROM party.entity_relationship r
                     WHERE r.status IN ('ACTIVE', 'SUSPENDED')
                       AND ((r.seller_entity_id = kernel.scope_entity() AND r.buyer_entity_id = p_entity_id)
                            OR (r.buyer_entity_id = kernel.scope_entity() AND r.seller_entity_id = p_entity_id))),
        false);
$$;

REVOKE ALL ON FUNCTION party.caller_trades_with(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION party.caller_trades_with(uuid) TO app_rw;

-- ---- 2. definer hygiene (RLS-13) ------------------------------------------------------------------

-- pg_temp last on every SECURITY DEFINER function of the schema (kernel V0086 says why); bodies
-- unchanged.
ALTER FUNCTION party.trading_standing(uuid)
    SET search_path = pg_catalog, party, kernel, pg_temp;
ALTER FUNCTION party.sync_entity_party_directory()
    SET search_path = pg_catalog, party, pg_temp;
