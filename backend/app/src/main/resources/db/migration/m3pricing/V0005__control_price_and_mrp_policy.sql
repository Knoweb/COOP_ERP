-- M3-06 and M3-07 (23A section 3; doc 23 section 3.4): the gazetted control prices, the
-- multi-MRP policy, and the read of the Federation's ADVISORY lists by every scope.

-- A control price is law from its gazette date (doc 23 section 1): the Federation enters it, it is
-- never edited, and the only change it admits is its end date (a later entry for the same scope
-- closes the previous one at effective_from - 1; a rescission ends it). Its history is the table.
-- Scope: 23A section 3 has a SKU or a governed tag (sku_id or tag_code, one of them). Tag scope is
-- deferred until M2 publishes a governed-tag query, so the table has sku_id NOT NULL and no
-- tag_code yet (a deviation, m3pricing/README.md): the migration that brings tag scope adds the
-- column, makes sku_id nullable and widens the exclusion to 23A's coalesce form.
CREATE TABLE pricing.control_price (
    control_price_id  uuid          PRIMARY KEY,
    sku_id            uuid          NOT NULL,
    ceiling_price     numeric(14,2) NOT NULL CHECK (ceiling_price > 0),
    ceiling_uom_code  varchar(10)   NOT NULL,
    effective_from    date          NOT NULL,
    effective_to      date,
    gazette_reference varchar(80)   NOT NULL CHECK (btrim(gazette_reference) <> ''),
    entered_by        uuid          NOT NULL,
    entered_at        timestamptz   NOT NULL DEFAULT now(),
    owner_entity_id   uuid          NOT NULL,              -- always the Federation
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    -- B-I6: no two ceilings of one scope overlap in time (its gist index also serves the lookups).
    EXCLUDE USING gist (sku_id WITH =,
                        daterange(effective_from, coalesce(effective_to, 'infinity'::date), '[]') WITH &&)
);

ALTER TABLE pricing.control_price ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.control_price FORCE ROW LEVEL SECURITY;

-- Readable by every authenticated scope (23A section 3): a society checks its retail lines and a
-- till its prices against the Federation's ceilings. Written by the Federation only (the handler
-- guards the caller; the policy keeps the row the caller's own).
CREATE POLICY everyone_reads ON pricing.control_price FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');
CREATE POLICY own_write ON pricing.control_price FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON pricing.control_price FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

-- 23A section 3: "never edited otherwise".
GRANT SELECT, INSERT ON pricing.control_price TO app_rw;
GRANT UPDATE (effective_to) ON pricing.control_price TO app_rw;

-- The multi-MRP policy of a SKU for an entity (doc 23 section 3.4): how the printed MRP of the
-- batches on the shelf bounds the selling price. One row per SKU and owner; SetMrpPolicy replaces
-- it, and the audit record keeps what it was. Tag scope (23A: tag_code, Federation only) is
-- deferred as for control prices: sku_id NOT NULL, no tag_code yet.
CREATE TABLE pricing.mrp_policy (
    policy_id          uuid          PRIMARY KEY,
    sku_id             uuid          NOT NULL,
    policy            text          NOT NULL CHECK (policy IN ('AUTO_LOWEST', 'BARCODE_RESOLVED', 'PICKER')),
    picker_gap_amount  numeric(14,2) CHECK (picker_gap_amount IS NULL OR picker_gap_amount >= 0),
    picker_gap_percent numeric(5,2)  CHECK (picker_gap_percent IS NULL OR picker_gap_percent BETWEEN 0 AND 100),
    owner_entity_id    uuid          NOT NULL,
    set_by             uuid          NOT NULL,
    set_at             timestamptz   NOT NULL DEFAULT now(),
    CHECK (policy = 'PICKER' OR (picker_gap_amount IS NULL AND picker_gap_percent IS NULL)),
    CONSTRAINT mrp_policy_scope_uq UNIQUE (sku_id, owner_entity_id)
);

ALTER TABLE pricing.mrp_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.mrp_policy FORCE ROW LEVEL SECURITY;

-- Readable by every authenticated scope: a society's effective policy falls back to the
-- Federation's row for the SKU (EffectivePolicy), and the policy is no secret of its owner.
CREATE POLICY everyone_reads ON pricing.mrp_policy FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');
CREATE POLICY own_write ON pricing.mrp_policy FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON pricing.mrp_policy FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

GRANT SELECT, INSERT ON pricing.mrp_policy TO app_rw;
GRANT UPDATE (policy, picker_gap_amount, picker_gap_percent, set_by, set_at) ON pricing.mrp_policy TO app_rw;

-- The Federation's ADVISORY lists, once published, are read by every scope (23A section 3: "advisory
-- lists ... readable by every authenticated scope"): the shelf price list shows them beside the
-- society's own prices.
CREATE POLICY advisory_read ON pricing.price_list FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND kind = 'ADVISORY'
           AND status IN ('PUBLISHED', 'SUPERSEDED'));
CREATE POLICY advisory_read ON pricing.price_list_line FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND EXISTS (SELECT 1
                         FROM pricing.price_list l
                        WHERE l.price_list_id = price_list_line.price_list_id
                          AND l.kind = 'ADVISORY'
                          AND l.status IN ('PUBLISHED', 'SUPERSEDED')));
