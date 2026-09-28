-- M8-04 rest and the shop's sales (28A section 3; doc 28 section 3): what the dashboard, the
-- exception queue and the demo's reports need from the events M4 and the tills now publish.
--
--   trade_document_event   gains the kinds of the payment, credit note, discrepancy and dispute
--                          events, and the columns due_date (invoice), committed_eta (accepted
--                          order), window_ends_at (discrepancy) and unapplied (payment receipt)
--   trade_line_fact        gains expected_qty on RECEIVED lines: the fill rate is received over
--                          expected, per GRN line
--   trade_document_link    which orders a delivery note carries and which GRNs an invoice bills:
--                          the committed ETA of an order meets the date its goods were received,
--                          and an accepted order is known to be invoiced
--   trade_settlement_fact  what paid, reopened or credited an invoice, one row per (source, invoice)
--   exposure_warning_event exposure.warning.v1, with the limit the relationship had then
--   shop_sale_fact         the till's receipts (receipt.issued.v1), one row per receipt
--   shop_sale_line_fact    and per line: the shop's sales by day and by item
--
-- Every table is written in the OWN scope of the event's owner and only ever inserted into
-- (ON CONFLICT DO NOTHING on the event's key), so a rebuild from the archive gives the same
-- rows. The trading tables are PARTY-readable and have no cost column; the shop's sales are
-- the owner's only (no party_read).

-- ---------------------------------------------------------------------------------------------
-- 1. More trading events on trade_document_event
ALTER TABLE reporting.trade_document_event DROP CONSTRAINT trade_document_event_event_kind_check;
ALTER TABLE reporting.trade_document_event ADD CONSTRAINT trade_document_event_event_kind_check CHECK (event_kind IN
    ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'DISPATCHED', 'CONFIRMED', 'ISSUED',
     'RAISED', 'SETTLED', 'DISPUTED', 'DISPUTE_RESOLVED', 'RECORDED', 'REVERSED', 'BOUNCED', 'CLEARED'));
ALTER TABLE reporting.trade_document_event DROP CONSTRAINT trade_document_event_doc_type_check;
ALTER TABLE reporting.trade_document_event ADD CONSTRAINT trade_document_event_doc_type_check CHECK (doc_type IN
    ('ORDER', 'DELIVERY_NOTE', 'GRN', 'INVOICE', 'CREDIT_NOTE', 'PAYMENT', 'DISCREPANCY'));

-- The invoice's due date (invoice.issued.v1 dueDate): what is overdue, and the ageing.
ALTER TABLE reporting.trade_document_event ADD COLUMN due_date date;
-- The date the seller committed to at acceptance (order.accepted.v1 committedEta).
ALTER TABLE reporting.trade_document_event ADD COLUMN committed_eta date;
-- The end of a discrepancy's window (discrepancy.raised.v1 windowEndsAt).
ALTER TABLE reporting.trade_document_event ADD COLUMN window_ends_at timestamptz;
-- What of a payment receipt no invoice took (payment_receipt.recorded.v1 unappliedAmount); on a
-- reversal, the unapplied part it took back, negative.
ALTER TABLE reporting.trade_document_event ADD COLUMN unapplied numeric(14,2);

-- ---------------------------------------------------------------------------------------------
-- 2. The expected quantity of a received line (grn.confirmed.v1 lines[].expectedQty)
ALTER TABLE reporting.trade_line_fact ADD COLUMN expected_qty numeric(14,3);

-- ---------------------------------------------------------------------------------------------
-- 3. Links between trading documents
CREATE TABLE reporting.trade_document_link (
    document_id            uuid        NOT NULL,
    linked_document_id     uuid        NOT NULL,
    -- ORDER: a delivery note carries the order; GRN: an invoice bills the GRN.
    link_kind              text        NOT NULL CHECK (link_kind IN ('ORDER', 'GRN')),
    owner_entity_id        uuid        NOT NULL,
    counterparty_entity_id uuid,
    PRIMARY KEY (document_id, linked_document_id)
);

CREATE INDEX trade_document_link_by_linked ON reporting.trade_document_link (linked_document_id);

ALTER TABLE reporting.trade_document_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.trade_document_link FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.trade_document_link FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON reporting.trade_document_link FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON reporting.trade_document_link FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON reporting.trade_document_link FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.trade_document_link FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.trade_document_link TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 4. What settled or credited an invoice
CREATE TABLE reporting.trade_settlement_fact (
    -- The receipt, the reversal or the credit note.
    source_document_id     uuid          NOT NULL,
    invoice_id             uuid          NOT NULL,
    -- PAYMENT (a receipt settled it), REVERSAL (a bounced cheque reopened it, negative), CREDIT
    -- (a credit note credited it).
    kind                   text          NOT NULL CHECK (kind IN ('PAYMENT', 'REVERSAL', 'CREDIT')),
    owner_entity_id        uuid          NOT NULL,
    counterparty_entity_id uuid,
    seller_entity_id       uuid          NOT NULL,
    buyer_entity_id        uuid          NOT NULL,
    business_date          date          NOT NULL,
    amount                 numeric(14,2) NOT NULL,
    event_id               uuid          NOT NULL,
    PRIMARY KEY (source_document_id, invoice_id, kind)
);

CREATE INDEX trade_settlement_fact_by_invoice ON reporting.trade_settlement_fact (invoice_id);

ALTER TABLE reporting.trade_settlement_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.trade_settlement_fact FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.trade_settlement_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON reporting.trade_settlement_fact FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON reporting.trade_settlement_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON reporting.trade_settlement_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.trade_settlement_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.trade_settlement_fact TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 5. Exposure warnings (exposure.warning.v1, published by the seller at order acceptance)
CREATE TABLE reporting.exposure_warning_event (
    event_id               uuid          PRIMARY KEY,
    owner_entity_id        uuid          NOT NULL,
    counterparty_entity_id uuid,
    relationship_id        uuid          NOT NULL,
    seller_entity_id       uuid          NOT NULL,
    buyer_entity_id        uuid          NOT NULL,
    amount                 numeric(14,2) NOT NULL,
    credit_limit           numeric(14,2),
    threshold_percent      int           NOT NULL,
    order_id               uuid,
    occurred_at            timestamptz   NOT NULL
);

CREATE INDEX exposure_warning_event_by_relationship
    ON reporting.exposure_warning_event (relationship_id, occurred_at);

ALTER TABLE reporting.exposure_warning_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.exposure_warning_event FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.exposure_warning_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON reporting.exposure_warning_event FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON reporting.exposure_warning_event FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON reporting.exposure_warning_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.exposure_warning_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.exposure_warning_event TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 6. The shop's sales (28A receipt_fact and receipt_line_fact, demo part: not partitioned, no
--    tenders, no cost). One row per receipt a till issued, and one per line.
CREATE TABLE reporting.shop_sale_fact (
    receipt_id       uuid          PRIMARY KEY,
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    business_date    date          NOT NULL,
    doc_number       text,
    net              numeric(14,2),
    tax              numeric(14,2),
    gross            numeric(14,2),
    lines            int           NOT NULL,
    occurred_at      timestamptz   NOT NULL,
    event_id         uuid          NOT NULL
);

CREATE INDEX shop_sale_fact_by_location_day ON reporting.shop_sale_fact (location_id, business_date);

CREATE TABLE reporting.shop_sale_line_fact (
    line_id          uuid          PRIMARY KEY,
    receipt_id       uuid          NOT NULL,
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    business_date    date          NOT NULL,
    sku_id           uuid          NOT NULL,
    qty              numeric(14,3) NOT NULL,
    line_total       numeric(14,2)
);

CREATE INDEX shop_sale_line_fact_by_location_day ON reporting.shop_sale_line_fact (location_id, business_date, sku_id);

ALTER TABLE reporting.shop_sale_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.shop_sale_fact FORCE ROW LEVEL SECURITY;
ALTER TABLE reporting.shop_sale_line_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.shop_sale_line_fact FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.shop_sale_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.shop_sale_fact FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON reporting.shop_sale_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.shop_sale_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

CREATE POLICY own_read ON reporting.shop_sale_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.shop_sale_line_fact FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON reporting.shop_sale_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.shop_sale_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.shop_sale_fact TO app_rw;
GRANT SELECT, INSERT ON reporting.shop_sale_line_fact TO app_rw;
