-- Code review wave 3, M1M2M3M5-26 (on PR #314, TILL-DATA-01): a Federation template amendment
-- reaches every society's tills.
--
-- definer_read on party.location TO app_seed, for security.role_holder_shops (m1security V0021).
-- That function runs as its owner, the migrator, whom FORCE ROW LEVEL SECURITY binds too; app_seed
-- is the migrator's group (kernel V0005), a role no application connection is a member of, as
-- security.user_role's definer_read (m1security V0010) lets the same function read the
-- assignments. The policy lives here because party.location is m1party's (rule R4: a module's
-- migrations touch its own schemas only). m1party runs before m1security, so the policy exists
-- before the function does.

CREATE POLICY definer_read ON party.location
    FOR SELECT
    TO app_seed
    USING (true);
