-- Code review wave 4, TD-01 and TD-03 (on PR #336, TILL-DATA-02): the shops a catalogue change
-- must reach, without a scope built by hand.
--
-- M2's change-log producer (M2ChangeLogFanOut, through CatalogueShops) must name the shops whose
-- tills receive a catalogue change: every shop of the federation for a SHARED item, the owning
-- entity's shops for a LOCAL one. It runs in the event owner's OWN scope, which may carry the
-- publisher's location, and party.location's own_read shows that entity's rows (or one shop) only.
-- It used to build a FEDERATION_VIEW scope itself and restore the connection by hand (TD-03).
--
-- party.change_log_shops(entity): the (entity, shop) pairs of SHOP locations in any status
-- (PLANNED, ONBOARDING, ACTIVE, DORMANT: a till enrols before its shop is activated and a dormant
-- shop's till keeps its version, TD-01), ids only. Who may ask, decided from kernel.scope_class():
--   - OWN only, with an entity; any other class (NONE, FEDERATION_VIEW, EXTERNAL_TIMEBOXED) gets
--     no row;
--   - entity NULL (every shop of the federation): the Federation (kernel.system_entity()) only;
--     a SHARED item is the Federation's (m2catalogue V0006);
--   - an entity: that entity itself, or the Federation.
--
-- The function runs as its owner, the migrator, and reads party.location through the
-- definer_read policy of V0016 (TO app_seed, the migrator's group).

CREATE FUNCTION party.change_log_shops(p_entity_id uuid)
RETURNS TABLE (shop_entity_id uuid, shop_location_id uuid)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, party, kernel, pg_temp
AS $$
    SELECT l.owner_entity_id, l.location_id
      FROM party.location l
     WHERE l.location_type = 'SHOP'
       AND kernel.scope_class() = 'OWN'
       AND kernel.scope_entity() IS NOT NULL
       AND (
            -- every shop of the federation, asked by the Federation
            (p_entity_id IS NULL
             AND kernel.system_entity() IS NOT NULL
             AND kernel.scope_entity() = kernel.system_entity())
            -- one entity's shops, asked by that entity or by the Federation
            OR (p_entity_id IS NOT NULL
                AND l.owner_entity_id = p_entity_id
                AND (p_entity_id = kernel.scope_entity()
                     OR (kernel.system_entity() IS NOT NULL
                         AND kernel.scope_entity() = kernel.system_entity())))
       )
     ORDER BY l.location_id
$$;

REVOKE ALL ON FUNCTION party.change_log_shops(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION party.change_log_shops(uuid) TO app_rw;
