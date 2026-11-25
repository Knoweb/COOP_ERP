-- M2-09 / CAT-06: Assortment maintainer (22A section 3, section 7.2; doc 22 section 3.8).
--
-- Location assortment defines the set of SKUs a location's till receives in its snapshot
-- and may scan at checkout. Materialised from retail price lists (M3), stock on-hand lots (M5),
-- local SKUs (M2), and governed core-range tags (M2). Deactivated SKUs with remaining lots
-- remain sellable until completely sold out (sell_through = true).

CREATE TABLE catalogue.location_assortment (
    location_id      uuid NOT NULL,
    sku_id           uuid NOT NULL REFERENCES catalogue.sku (sku_id),
    reasons          text[] NOT NULL,
    sell_through     boolean NOT NULL DEFAULT false,
    since            timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    owner_entity_id  uuid NOT NULL,
    PRIMARY KEY (location_id, sku_id)
);

CREATE INDEX location_assortment_owner_idx
    ON catalogue.location_assortment (owner_entity_id);

CREATE INDEX location_assortment_sku_idx
    ON catalogue.location_assortment (sku_id);

ALTER TABLE catalogue.location_assortment ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.location_assortment FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.location_assortment FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON catalogue.location_assortment FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_update ON catalogue.location_assortment FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_delete ON catalogue.location_assortment FOR DELETE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON catalogue.location_assortment FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON catalogue.location_assortment FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON catalogue.location_assortment FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON catalogue.location_assortment TO app_rw;
