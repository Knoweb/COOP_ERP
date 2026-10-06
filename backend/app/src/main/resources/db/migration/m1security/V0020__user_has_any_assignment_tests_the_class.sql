-- Wave 2 of the code review (RLS-17, the schema rule of
-- docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md (1)), decided 6 October 2026 on the
-- architect's delegation: docs/progress/deviations/2026-10-06-wave2-last-definer-functions-17.md.
--
-- security.user_has_any_assignment (V0012, search_path fixed by V0018) is the definer helper of own_read
-- and own_update on security.app_user: a user with no assignment yet is visible at every location of
-- its entity. It is executable by app_rw and tested no class, so any session, NONE included, could ask
-- whether any user id of any entity holds an assignment. It now answers false to every class but OWN.
-- Inside the two policies the class is already OWN (each tests it first), so what they admit is
-- unchanged.
CREATE OR REPLACE FUNCTION security.user_has_any_assignment(p_user_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, security, pg_temp
AS $$
    SELECT kernel.scope_class() IS NOT DISTINCT FROM 'OWN'
       AND EXISTS (SELECT 1 FROM security.user_role WHERE user_id = p_user_id)
$$;

REVOKE ALL ON FUNCTION security.user_has_any_assignment(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION security.user_has_any_assignment(uuid) TO app_rw;
