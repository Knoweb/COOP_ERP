-- The seed loader connects as the migrator, a member of the group role app_seed (kernel V0005;
-- decided by the architect, 23 September 2026; see M1SeedLoader). V0002 had let the
-- application role write the permission catalogue behind a scope class "SYSTEM_SEED" that any
-- application code could set with one session variable. Those policies and grants are undone
-- here; a merged migration is never edited.
--
-- What V0001 grants stays: the application may INSERT and UPDATE security.role,
-- role_permission and sod_pair, because an entity authors its own roles (M1-08). Only the
-- catalogue and its version table go back to read-only for app_rw, as V0001 had them.

DROP POLICY IF EXISTS seed_write ON security.permission;
DROP POLICY IF EXISTS seed_write ON security.permission_catalogue_version;
DROP POLICY IF EXISTS seed_write ON security.role;
DROP POLICY IF EXISTS seed_write ON security.role_permission;
DROP POLICY IF EXISTS seed_write ON security.sod_pair;

REVOKE INSERT, UPDATE ON security.permission FROM app_rw;
REVOKE INSERT ON security.permission_catalogue_version FROM app_rw;

-- The seed path: members of app_seed may read and write the reference tables the loader
-- fills. The migrator owns the tables, so it needs no GRANT, only a policy that admits it
-- under FORCE ROW LEVEL SECURITY.
CREATE POLICY seed_reference ON security.permission
    FOR ALL TO app_seed USING (true) WITH CHECK (true);
CREATE POLICY seed_reference ON security.permission_catalogue_version
    FOR ALL TO app_seed USING (true) WITH CHECK (true);
CREATE POLICY seed_reference ON security.role
    FOR ALL TO app_seed USING (true) WITH CHECK (true);
CREATE POLICY seed_reference ON security.role_permission
    FOR ALL TO app_seed USING (true) WITH CHECK (true);
CREATE POLICY seed_reference ON security.sod_pair
    FOR ALL TO app_seed USING (true) WITH CHECK (true);
