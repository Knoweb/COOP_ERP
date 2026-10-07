-- Wave 2 of the code review (RLS-08, M8-03, M8-04, M8-07, M8-08), decided 6 October 2026 on the
-- architect's delegation: docs/progress/deviations/2026-10-06-wave2-trading-projections.md,
-- CR-28A-2.
--
--   1. The five trading projections carry the owner's location_id and the template's location
--      line (RLS_POLICY_TEMPLATE.md): a shop-scoped session reads the rows of its own shop and
--      nothing entity-wide, as it reads kernel.document since kernel V0061. party_read takes the
--      template's form, the location line on the owner's side only.
--   2. trade_document_event is keyed by event_id: a second dispute after a resolution is a row of
--      its own, not dropped by the old (document_id, event_kind, owner_entity_id) key, which stays
--      as a plain index. The queries take the latest event of a kind (TradeSql.EVENTS).
--   3. shop_sale_line_fact is keyed by (receipt_id, line_no): the shipping till never sends a
--      line_id, so every line of a real till receipt was skipped.
--   4. credit_limit_fact: every credit_limit.changed.v1, the opening limit included (M1 publishes
--      it at activation since wave 2, PR 11); the exposure view reads the current limit of each
--      pair from it.
--
-- Rows written before this migration: their location_id stays NULL until a rebuild (M8-05, not
-- built), so a shop session does not see them and an entity-wide one does: incomplete, never
-- wrong. The module README says so. No definer function is added.

-- ---------------------------------------------------------------------------------------------
-- 1. The owner's location on the trading projections, and the template's policies
ALTER TABLE reporting.trade_document_event ADD COLUMN location_id uuid;
ALTER TABLE reporting.trade_line_fact ADD COLUMN location_id uuid;
ALTER TABLE reporting.trade_settlement_fact ADD COLUMN location_id uuid;
ALTER TABLE reporting.trade_document_link ADD COLUMN location_id uuid;
ALTER TABLE reporting.exposure_warning_event ADD COLUMN location_id uuid;

DROP POLICY own_read ON reporting.trade_document_event;
DROP POLICY own_write ON reporting.trade_document_event;
DROP POLICY party_read ON reporting.trade_document_event;
CREATE POLICY own_read ON reporting.trade_document_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.trade_document_event FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY party_read ON reporting.trade_document_event FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND ((owner_entity_id = kernel.scope_entity()
                 AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                OR counterparty_entity_id = kernel.scope_entity()));

DROP POLICY own_read ON reporting.trade_line_fact;
DROP POLICY own_write ON reporting.trade_line_fact;
DROP POLICY party_read ON reporting.trade_line_fact;
CREATE POLICY own_read ON reporting.trade_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.trade_line_fact FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY party_read ON reporting.trade_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND ((owner_entity_id = kernel.scope_entity()
                 AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                OR counterparty_entity_id = kernel.scope_entity()));

DROP POLICY own_read ON reporting.trade_settlement_fact;
DROP POLICY own_write ON reporting.trade_settlement_fact;
DROP POLICY party_read ON reporting.trade_settlement_fact;
CREATE POLICY own_read ON reporting.trade_settlement_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.trade_settlement_fact FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY party_read ON reporting.trade_settlement_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND ((owner_entity_id = kernel.scope_entity()
                 AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                OR counterparty_entity_id = kernel.scope_entity()));

DROP POLICY own_read ON reporting.trade_document_link;
DROP POLICY own_write ON reporting.trade_document_link;
DROP POLICY party_read ON reporting.trade_document_link;
CREATE POLICY own_read ON reporting.trade_document_link FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.trade_document_link FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY party_read ON reporting.trade_document_link FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND ((owner_entity_id = kernel.scope_entity()
                 AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                OR counterparty_entity_id = kernel.scope_entity()));

DROP POLICY own_read ON reporting.exposure_warning_event;
DROP POLICY own_write ON reporting.exposure_warning_event;
DROP POLICY party_read ON reporting.exposure_warning_event;
CREATE POLICY own_read ON reporting.exposure_warning_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.exposure_warning_event FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY party_read ON reporting.exposure_warning_event FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND ((owner_entity_id = kernel.scope_entity()
                 AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                OR counterparty_entity_id = kernel.scope_entity()));

-- ---------------------------------------------------------------------------------------------
-- 2. One row per event on trade_document_event
ALTER TABLE reporting.trade_document_event DROP CONSTRAINT trade_document_event_pkey;
ALTER TABLE reporting.trade_document_event ADD CONSTRAINT trade_document_event_pkey PRIMARY KEY (event_id);
CREATE INDEX trade_document_event_by_document
    ON reporting.trade_document_event (document_id, event_kind, owner_entity_id);

-- ---------------------------------------------------------------------------------------------
-- 3. The till's lines by their number
ALTER TABLE reporting.shop_sale_line_fact ADD COLUMN line_no integer;
-- Rows projected before carried a line_id (tests and the demo loader's bundles; a real till's
-- lines were never inserted) and no number. They are numbered here in the order of their line_id
-- within the receipt so that the new key holds; their quantities and totals are unchanged, so the
-- sales by item are too. The till's own numbers come back with a rebuild (M8-05). The table is
-- FORCE ROW LEVEL SECURITY, which binds its owner too: lifted for this one statement only.
ALTER TABLE reporting.shop_sale_line_fact NO FORCE ROW LEVEL SECURITY;
UPDATE reporting.shop_sale_line_fact f
   SET line_no = n.line_no
  FROM (SELECT line_id, row_number() OVER (PARTITION BY receipt_id ORDER BY line_id) AS line_no
          FROM reporting.shop_sale_line_fact) n
 WHERE f.line_id = n.line_id;
ALTER TABLE reporting.shop_sale_line_fact FORCE ROW LEVEL SECURITY;
ALTER TABLE reporting.shop_sale_line_fact ALTER COLUMN line_no SET NOT NULL;
ALTER TABLE reporting.shop_sale_line_fact ADD CONSTRAINT shop_sale_line_fact_line_no_check CHECK (line_no >= 1);
ALTER TABLE reporting.shop_sale_line_fact DROP CONSTRAINT shop_sale_line_fact_pkey;
ALTER TABLE reporting.shop_sale_line_fact ALTER COLUMN line_id DROP NOT NULL;
ALTER TABLE reporting.shop_sale_line_fact ADD CONSTRAINT shop_sale_line_fact_pkey PRIMARY KEY (receipt_id, line_no);

-- ---------------------------------------------------------------------------------------------
-- 4. Credit limits (credit_limit.changed.v1)
CREATE TABLE reporting.credit_limit_fact (
    event_id                 uuid          PRIMARY KEY,
    -- The relationship row that carries the limit; an amendment makes a new row, so the pair
    -- (seller, buyer) is what a current limit belongs to.
    relationship_id          uuid          NOT NULL,
    previous_relationship_id uuid,
    owner_entity_id          uuid          NOT NULL,
    counterparty_entity_id   uuid,
    -- A relationship is the entity's, never a shop's: NULL unless the event was published in a
    -- shop's session, so a shop session reads no limit and the exposure is an entity-wide figure.
    location_id              uuid,
    seller_entity_id         uuid          NOT NULL,
    buyer_entity_id          uuid          NOT NULL,
    -- NULL when the limit was taken away.
    credit_limit             numeric(14,2),
    previous_credit_limit    numeric(14,2),
    effective_from           date,
    occurred_at              timestamptz   NOT NULL
);

CREATE INDEX credit_limit_fact_by_pair
    ON reporting.credit_limit_fact (seller_entity_id, buyer_entity_id, occurred_at);

ALTER TABLE reporting.credit_limit_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.credit_limit_fact FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.credit_limit_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.credit_limit_fact FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY party_read ON reporting.credit_limit_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND ((owner_entity_id = kernel.scope_entity()
                 AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON reporting.credit_limit_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.credit_limit_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.credit_limit_fact TO app_rw;
