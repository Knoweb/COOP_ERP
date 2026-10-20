-- M2-07 / CAT-03: sku_alias and promotion/merge policies (22A section 3, section 6; doc 22 section 6.3).
--
-- Preserves an originating entity's history when a local SKU is promoted to shared or merged
-- into an existing shared SKU (FR-SKU-100, B-10).

CREATE TABLE catalogue.sku_alias (
    alias_sku_id     uuid PRIMARY KEY REFERENCES catalogue.sku (sku_id),
    canonical_sku_id uuid NOT NULL REFERENCES catalogue.sku (sku_id),
    effective_from   date NOT NULL,
    owner_entity_id  uuid NOT NULL
);

CREATE INDEX sku_alias_canonical_idx ON catalogue.sku_alias (canonical_sku_id);
CREATE INDEX sku_alias_owner_idx ON catalogue.sku_alias (owner_entity_id);

ALTER TABLE catalogue.sku_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.sku_alias FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.sku_alias FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_write ON catalogue.sku_alias FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY fed_view ON catalogue.sku_alias FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON catalogue.sku_alias FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- 22A section 3: readable by all authenticated scopes
CREATE POLICY authenticated_read ON catalogue.sku_alias FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');

CREATE POLICY fed_write ON catalogue.sku_alias FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity()));

CREATE POLICY fed_update ON catalogue.sku_alias FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_entity() = (SELECT kernel.system_entity()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity()));

REVOKE ALL PRIVILEGES ON catalogue.sku_alias FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON catalogue.sku_alias TO app_rw;

-- Promotion and merge policy on catalogue.sku: the Federation takes over a LOCAL row
-- (PromoteToShared updates status to SHARED and owner to Federation; MergeIntoShared updates
-- status to INACTIVE with prior_status LOCAL).
CREATE POLICY fed_promote ON catalogue.sku FOR ALL TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_entity() = (SELECT kernel.system_entity())
           AND (status = 'LOCAL' OR (status = 'INACTIVE' AND prior_status = 'LOCAL'))
           AND coalesce(current_setting('app.fed_promote', true), '') = 'true')
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity())
                AND coalesce(current_setting('app.fed_promote', true), '') = 'true'
                AND (
                    (status = 'SHARED' AND owner_entity_id = (SELECT kernel.system_entity()))
                    OR
                    (status = 'INACTIVE' AND prior_status = 'LOCAL')
                ));

-- Barcode migration on catalogue.sku_barcode: during MergeIntoShared, the Federation
-- updates barcodes of the local SKU (re-pointing to canonical SKU or retiring on collision).
GRANT UPDATE (sku_id) ON catalogue.sku_barcode TO app_rw;

CREATE POLICY fed_merge_barcode ON catalogue.sku_barcode FOR ALL TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND kernel.scope_entity() = (SELECT kernel.system_entity())
           AND coalesce(current_setting('app.fed_promote', true), '') = 'true')
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND kernel.scope_entity() = (SELECT kernel.system_entity())
                AND coalesce(current_setting('app.fed_promote', true), '') = 'true');


