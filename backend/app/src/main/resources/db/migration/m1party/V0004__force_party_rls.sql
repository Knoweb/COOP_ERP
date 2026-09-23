-- Complete RLS enforcement for M1 internal party helper tables.
--
-- federation_identity and entity_party_directory are maintained by
-- SECURITY DEFINER trigger functions. FORCE RLS therefore requires
-- narrow policies for the migration/function owner; otherwise the
-- trigger itself is blocked by RLS.
--
-- app_rw keeps only the SELECT policies and grants defined by the
-- earlier migrations. These policies do not grant app_rw write access.

-- -------------------------------------------------------------------
-- federation_identity
-- -------------------------------------------------------------------

CREATE POLICY internal_owner_read
    ON party.federation_identity
    FOR SELECT
    TO CURRENT_USER
    USING (true);

CREATE POLICY internal_owner_insert
    ON party.federation_identity
    FOR INSERT
    TO CURRENT_USER
    WITH CHECK (true);

CREATE POLICY internal_owner_update
    ON party.federation_identity
    FOR UPDATE
    TO CURRENT_USER
    USING (true)
    WITH CHECK (true);

ALTER TABLE party.federation_identity
    FORCE ROW LEVEL SECURITY;


-- -------------------------------------------------------------------
-- entity_party_directory
-- -------------------------------------------------------------------

CREATE POLICY internal_owner_read
    ON party.entity_party_directory
    FOR SELECT
    TO CURRENT_USER
    USING (true);

CREATE POLICY internal_owner_insert
    ON party.entity_party_directory
    FOR INSERT
    TO CURRENT_USER
    WITH CHECK (true);

CREATE POLICY internal_owner_update
    ON party.entity_party_directory
    FOR UPDATE
    TO CURRENT_USER
    USING (true)
    WITH CHECK (true);

ALTER TABLE party.entity_party_directory
    FORCE ROW LEVEL SECURITY;