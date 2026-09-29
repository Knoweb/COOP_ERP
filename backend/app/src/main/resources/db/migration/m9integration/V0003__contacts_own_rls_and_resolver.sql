-- notification_contact is personal data of a kind (an e-mail address, a telephone number): an
-- entity reads and writes its own contacts only (decided on the architect's delegation,
-- 29 September 2026), in place of V0001's directory_read. The dispatcher still reaches the other
-- party of a document (the buyer's accounts desk on the seller's invoice) through one narrow door:
-- integration.notification_recipients(entity, role), a SECURITY DEFINER function owned by the
-- migrator that answers the ACTIVE addresses of one entity for one role code, and nothing else.
-- The table is forced under RLS for its owner too, so the function reads through seed_resolver,
-- a policy for app_seed (the migrator's group), never through a policy of app_rw.

DROP POLICY directory_read ON integration.notification_contact;

CREATE POLICY own_read ON integration.notification_contact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.notification_contact FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.notification_contact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY seed_resolver ON integration.notification_contact FOR SELECT TO app_seed
    USING (true);

GRANT INSERT ON integration.notification_contact TO app_rw;
GRANT SELECT ON integration.notification_contact TO app_seed;

CREATE FUNCTION integration.notification_recipients(p_entity uuid, p_role text)
    RETURNS TABLE (channel text, address text, language text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT c.channel, c.address, btrim(c.language)
      FROM integration.notification_contact c
     WHERE c.owner_entity_id = p_entity
       AND c.role_code = p_role
       AND c.status = 'ACTIVE'
     ORDER BY c.channel, c.contact_id
$$;

REVOKE ALL ON FUNCTION integration.notification_recipients(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration.notification_recipients(uuid, text) TO app_rw;
