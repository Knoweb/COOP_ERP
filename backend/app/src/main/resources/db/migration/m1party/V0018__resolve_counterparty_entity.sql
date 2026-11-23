-- CAT-14: bulk credit limit imports across trading relationships.
-- A seller (e.g. Distributor) setting relationship credit limits from a sheet needs to resolve
-- the counterparty entity (buyer) by entity_code or entity_id across tenants.
--
-- Under row-level security, party.entity's own_read policy exposes only the caller's own entity,
-- so this narrow helper function reads party.entity through the migrator's app_seed standing_read
-- policy (m1party V0007) and returns the entity_id to an OWN or FEDERATION_VIEW caller.

CREATE FUNCTION party.resolve_counterparty_entity(p_identifier text)
RETURNS uuid
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, party, kernel, pg_temp
AS $$
    SELECT e.entity_id
      FROM party.entity e
     WHERE (e.entity_code = trim(p_identifier) OR e.entity_id::text = trim(p_identifier))
       AND kernel.scope_class() IN ('OWN', 'FEDERATION_VIEW')
       AND kernel.scope_entity() IS NOT NULL
     LIMIT 1;
$$;

REVOKE ALL ON FUNCTION party.resolve_counterparty_entity(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION party.resolve_counterparty_entity(text) TO app_rw;
