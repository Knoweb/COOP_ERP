-- M1-09 external grants (21A section 6; doc 21 sections 3.7, 4.6 and flow 6.5).
--
-- Two things the security schema did not have for an EXTERNAL_TIMEBOXED caller.
--
-- 1. Resolving a grant. A grant row belongs to the Federation (owner_entity_id), so under
--    V0001 only the Federation's OWN scope could read it. The kernel resolves the entities of
--    an external user before that user has any scope at all (ExternalGrantQueries
--    .activeGrantedEntities, for the token mapper of K-02): the grantee must be able to read its
--    own grants. That is the third case of RLS_POLICY_TEMPLATE.md, "a user's own rows regardless
--    of tenant": a policy on app.user_id, which the kernel's connection customizer sets for the
--    request's user, as kernel V0010 does for the idempotency key. Read only; the grantee never
--    writes a grant.
--
-- 2. Reading the granted entity. The template gives every operational table an ext_view
--    policy; the security tables of V0001 have none, so a regulator holding a grant read
--    nothing here. The entity's roles, their permissions, its assignments and its segregation
--    pairs (who may do what: the substance of an inspection) now admit an EXTERNAL_TIMEBOXED
--    caller for the granted entities, read only. security.app_user is left out on purpose: it
--    carries pin_hash and provider_subject, which no external reader needs and which RLS cannot
--    hide by column; a regulator's view of staff names waits for a masking view.
--
-- The party schema has no ext_view either (M1RlsIntegrationTest pins it for party.location);
-- that is an m1party migration, outside this one's lane.

CREATE POLICY grantee_read
    ON security.external_grant
    FOR SELECT
    TO app_rw
    USING (
        grantee_user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
    );

CREATE POLICY ext_view
    ON security.role
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (kernel.granted_entities())
    );

CREATE POLICY ext_view
    ON security.role_permission
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND EXISTS (
            SELECT 1
              FROM security.role r
             WHERE r.role_id = role_permission.role_id
               AND r.owner_entity_id = ANY (kernel.granted_entities())
        )
    );

CREATE POLICY ext_view
    ON security.user_role
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND scope_entity_id = ANY (kernel.granted_entities())
    );

CREATE POLICY ext_view
    ON security.sod_pair
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (kernel.granted_entities())
    );
