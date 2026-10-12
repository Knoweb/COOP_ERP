-- FIX-13: Adds the previously missing external read policy to hello.greeting.
-- EXTERNAL_TIMEBOXED callers may read only rows owned by entities in kernel.granted_entities().
-- The policy is read-only and follows db/migration/RLS_POLICY_TEMPLATE.md.
-- This is a new migration because previously applied migrations must not be modified.
CREATE POLICY ext_view ON hello.greeting FOR SELECT TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (kernel.granted_entities())
    );
