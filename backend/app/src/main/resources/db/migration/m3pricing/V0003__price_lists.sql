-- M3-03 (23A section 3; doc 23 section 3.1): the price list and its lines, replacing the
-- scaffolder's copy of the hello table. The other tables of 23A section 3 (discount_rule,
-- control_price, mrp_policy, adjustment_type, label_job) arrive with the tickets that use them
-- (M3-05 to M3-10); deferred for the demo, see docs/PROGRESS.md.
--
-- The scaffold table held greetings and nothing refers to it (M1's relationships name price
-- list ids without a foreign key, 21A section 3.1), so it is dropped, not migrated.
DROP TABLE pricing.price_list;

-- One row per version of a list (23A section 3: version, source_version_id). Deviation, recorded
-- in the module README: root_price_list_id, the id of version 1, which every later version
-- carries. A relationship binds that id (M1 entity_relationship.price_list_id), so a new version
-- needs no change to the relationships that use the list, and the buyer's read policy below and
-- the trade price lookup find every version of the list without walking source_version_id.
CREATE TABLE pricing.price_list (
    price_list_id      uuid        PRIMARY KEY,
    owner_entity_id    uuid        NOT NULL,
    kind               text        NOT NULL CHECK (kind IN ('TRADE', 'RETAIL', 'ADVISORY')),
    name               text        NOT NULL CHECK (btrim(name) <> ''),
    version            int         NOT NULL DEFAULT 1 CHECK (version >= 1),
    root_price_list_id uuid        NOT NULL,
    source_version_id  uuid        REFERENCES pricing.price_list (price_list_id),
    status             text        NOT NULL DEFAULT 'DRAFT'
                                   CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'WITHDRAWN')),
    apply_from         date,
    published_by       uuid,
    published_at       timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT price_list_version_uq UNIQUE (root_price_list_id, version),
    CONSTRAINT price_list_published_has_date CHECK (status = 'DRAFT' OR status = 'WITHDRAWN' OR apply_from IS NOT NULL)
);

-- One PUBLISHED and one DRAFT RETAIL list per MPCS (23A section 3); one DRAFT per list for any kind.
CREATE UNIQUE INDEX one_published_retail ON pricing.price_list (owner_entity_id)
    WHERE kind = 'RETAIL' AND status = 'PUBLISHED';
CREATE UNIQUE INDEX one_draft_retail ON pricing.price_list (owner_entity_id)
    WHERE kind = 'RETAIL' AND status = 'DRAFT';
CREATE UNIQUE INDEX one_draft_per_list ON pricing.price_list (root_price_list_id)
    WHERE status = 'DRAFT';
CREATE INDEX price_list_root ON pricing.price_list (root_price_list_id, version DESC);

CREATE TABLE pricing.price_list_line (
    line_id         uuid          PRIMARY KEY,
    price_list_id   uuid          NOT NULL REFERENCES pricing.price_list (price_list_id),
    sku_id          uuid          NOT NULL,
    uom_code        varchar(10)   NOT NULL,
    tier_from_qty   numeric(14,3) NOT NULL DEFAULT 0 CHECK (tier_from_qty >= 0),
    price           numeric(14,4) NOT NULL CHECK (price >= 0),
    -- A draft line carries the day it was written; publication sets it to apply_from.
    effective_from  date          NOT NULL,
    effective_to    date,
    owner_entity_id uuid          NOT NULL,
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    EXCLUDE USING gist (price_list_id WITH =, sku_id WITH =, uom_code WITH =, tier_from_qty WITH =,
                        daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') WITH &&)
);

CREATE INDEX pll_lookup ON pricing.price_list_line (price_list_id, sku_id, uom_code, effective_from DESC);

-- A line is written only while its list is a DRAFT: publication fixes it (doc 23 section 3.1,
-- "nothing is edited"). The handlers guard it; this trigger is the backstop for any other path.
CREATE FUNCTION pricing.line_of_a_draft() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    list_status text;
BEGIN
    SELECT l.status INTO list_status
      FROM pricing.price_list l
     WHERE l.price_list_id = coalesce(NEW.price_list_id, OLD.price_list_id);
    IF list_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'price list % is not a draft', coalesce(NEW.price_list_id, OLD.price_list_id)
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN coalesce(NEW, OLD);
END;
$$;

CREATE TRIGGER price_list_line_draft_only
    BEFORE INSERT OR UPDATE OR DELETE ON pricing.price_list_line
    FOR EACH ROW EXECUTE FUNCTION pricing.line_of_a_draft();

-- Row-level security: the template of db/migration/RLS_POLICY_TEMPLATE.md (no location column),
-- plus buyer_read (23A section 3: "PARTY read on price_list/line for the relationship's buyer").
ALTER TABLE pricing.price_list ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.price_list FORCE ROW LEVEL SECURITY;
ALTER TABLE pricing.price_list_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.price_list_line FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON pricing.price_list FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON pricing.price_list FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON pricing.price_list FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON pricing.price_list FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON pricing.price_list FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
-- The buyer of a relationship reads the published versions of the TRADE list the relationship
-- binds, and nothing else of the seller's (M4 prices the buyer's order in the buyer's scope, 24A
-- section 6). The relationship row is read under the buyer's own policies (party_read of M1).
CREATE POLICY buyer_read ON pricing.price_list FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND kind = 'TRADE'
           AND status IN ('PUBLISHED', 'SUPERSEDED')
           AND EXISTS (SELECT 1
                         FROM party.entity_relationship r
                        WHERE r.price_list_id = price_list.root_price_list_id
                          AND r.seller_entity_id = price_list.owner_entity_id
                          AND r.buyer_entity_id = kernel.scope_entity()));

CREATE POLICY own_read ON pricing.price_list_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON pricing.price_list_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON pricing.price_list_line FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_delete ON pricing.price_list_line FOR DELETE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON pricing.price_list_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON pricing.price_list_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
-- A line is visible to a buyer when its list is (the list's own policies decide, buyer_read above).
CREATE POLICY buyer_read ON pricing.price_list_line FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND owner_entity_id <> kernel.scope_entity()
           AND EXISTS (SELECT 1
                         FROM pricing.price_list l
                        WHERE l.price_list_id = price_list_line.price_list_id
                          AND l.kind = 'TRADE'
                          AND l.status IN ('PUBLISHED', 'SUPERSEDED')));

-- Grants: a list changes status (publish, supersede) and nothing else; a draft line is replaced
-- (delete and insert) and dated at publication. No delete of a list, no other column update.
GRANT SELECT, INSERT ON pricing.price_list TO app_rw;
GRANT UPDATE (status, apply_from, published_by, published_at) ON pricing.price_list TO app_rw;
GRANT SELECT, INSERT, DELETE ON pricing.price_list_line TO app_rw;
GRANT UPDATE (effective_from) ON pricing.price_list_line TO app_rw;
