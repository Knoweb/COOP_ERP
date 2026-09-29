-- The society card names its responsible officer (demo walkthrough, 29 September 2026).
--
-- The officer is a user of the society, and a Federation session may not read another entity's
-- users (m1security V0012), so the card showed the officer's id. Decided on the architect's
-- delegation (29 September 2026): the officer's display name is part of the society register
-- (doc 21 / 21A: the register shows who is accountable), so whoever may read the entity record
-- may read that one name. User visibility is not widened: this function answers one text, the
-- display name of the user appointed on one entity, and nothing else.
--
-- The caller passes the entity and the officer id it read from the entity row under its own
-- scope; the function answers only when that user is the officer appointed on that entity. The
-- officer id is on the entity row alone, so only a caller that could read the row can ask; a
-- guessed or stale pair answers NULL, and so does a session with no scope class.
--
-- The function runs as the migrator; both tables are read through the app_seed read policies
-- already in place (party.entity standing_read, m1party V0007; security.app_user definer_read,
-- m1security V0010), the same narrow-function pattern as party.trading_standing.
CREATE FUNCTION security.appointed_officer_name(p_entity_id uuid, p_officer_user_id uuid)
    RETURNS text
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
    SELECT u.display_name
      FROM party.entity e
      JOIN security.app_user u ON u.user_id = e.responsible_officer_user_id
     WHERE e.entity_id = p_entity_id
       AND e.responsible_officer_user_id = p_officer_user_id
       AND kernel.scope_class() <> 'NONE'
$$;

REVOKE ALL ON FUNCTION security.appointed_officer_name(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION security.appointed_officer_name(uuid, uuid) TO app_rw;
