-- CR-17A-3: own_read and own_write test the scope class as well as the entity. Without the
-- class test a FEDERATION_VIEW or EXTERNAL_TIMEBOXED caller, whose scope entity is set too,
-- read and wrote the rows of that entity through own_*, and doc 18 section 3.7 makes those
-- classes read-only. The template is db/migration/RLS_POLICY_TEMPLATE.md and the RLS matrix
-- test proves it. A merged migration is never edited: the policies are altered here.
ALTER POLICY own_read ON hello.greeting
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

ALTER POLICY own_write ON hello.greeting
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
