-- Seed loader requires grants and RLS bypass for templates and catalogue data
-- as it runs as the app_rw role during application startup.

GRANT INSERT, UPDATE ON security.permission TO app_rw;
GRANT INSERT ON security.permission_catalogue_version TO app_rw;

CREATE POLICY seed_write ON security.permission
    FOR ALL
    TO app_rw
    USING (kernel.scope_class() = 'SYSTEM_SEED')
    WITH CHECK (kernel.scope_class() = 'SYSTEM_SEED');

CREATE POLICY seed_write ON security.permission_catalogue_version
    FOR ALL
    TO app_rw
    USING (kernel.scope_class() = 'SYSTEM_SEED')
    WITH CHECK (kernel.scope_class() = 'SYSTEM_SEED');

CREATE POLICY seed_write ON security.role
    FOR ALL
    TO app_rw
    USING (kernel.scope_class() = 'SYSTEM_SEED')
    WITH CHECK (kernel.scope_class() = 'SYSTEM_SEED');

CREATE POLICY seed_write ON security.role_permission
    FOR ALL
    TO app_rw
    USING (kernel.scope_class() = 'SYSTEM_SEED')
    WITH CHECK (kernel.scope_class() = 'SYSTEM_SEED');

CREATE POLICY seed_write ON security.sod_pair
    FOR ALL
    TO app_rw
    USING (kernel.scope_class() = 'SYSTEM_SEED')
    WITH CHECK (kernel.scope_class() = 'SYSTEM_SEED');
