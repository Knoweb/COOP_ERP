-- The trading projections of the demo (28A section 3, trade_document_fact; doc 28 section 3:
-- "M4 events -> trade documents by relationship, status and value"), M8-04 in part.
--
--   trade_document_event   one row per trading event on a document: submitted, accepted,
--                          rejected, cancelled, dispatched, confirmed (GRN), issued (invoice)
--   trade_line_fact        one row per line that moved goods or commitments: ACCEPTED (the
--                          seller's accepted order lines) and RECEIVED (the buyer's GRN lines)
--
-- Both are written by the consumer in the OWN scope of the event's owner (the party whose
-- command published it) and read by the counterparty through party_read: the seller of an
-- order sees the buyer's submission and the buyer the seller's acceptance, each on its own row.
-- That is the departure from 28A's trade_document_fact, one updated row per document: a row
-- owned by the buyer cannot be updated in the seller's scope under row-level security, and
-- an insert-only row per event is idempotent by its key. The document's status is the latest
-- of its events (the reports' queries).
--
-- No cost column: both tables are PARTY-readable (28A section 6, "masking is structural").
-- value is quantity times the trade price the two parties agreed, which both see on the
-- invoice.

-- ---------------------------------------------------------------------------------------------
-- 1. Trading events per document
CREATE TABLE reporting.trade_document_event (
    document_id            uuid          NOT NULL,
    event_kind             text          NOT NULL CHECK (event_kind IN
                               ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'DISPATCHED', 'CONFIRMED', 'ISSUED')),
    owner_entity_id        uuid          NOT NULL,
    doc_type               text          NOT NULL CHECK (doc_type IN ('ORDER', 'DELIVERY_NOTE', 'GRN', 'INVOICE')),
    doc_number             text,
    counterparty_entity_id uuid,
    seller_entity_id       uuid          NOT NULL,
    buyer_entity_id        uuid          NOT NULL,
    relationship_id        uuid,
    -- The document this one follows: a GRN's delivery note.
    reference_document_id  uuid,
    business_date          date          NOT NULL,
    occurred_at            timestamptz   NOT NULL,
    net                    numeric(14,2),
    tax                    numeric(14,2),
    gross                  numeric(14,2),
    event_id               uuid          NOT NULL,
    PRIMARY KEY (document_id, event_kind, owner_entity_id)
);

CREATE INDEX trade_document_event_by_owner ON reporting.trade_document_event (owner_entity_id, doc_type, business_date);
CREATE INDEX trade_document_event_by_counterparty
    ON reporting.trade_document_event (counterparty_entity_id, doc_type, business_date);
CREATE INDEX trade_document_event_by_reference ON reporting.trade_document_event (reference_document_id);

ALTER TABLE reporting.trade_document_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.trade_document_event FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.trade_document_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON reporting.trade_document_event FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON reporting.trade_document_event FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON reporting.trade_document_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.trade_document_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.trade_document_event TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 2. Trading lines: the volume by seller, buyer, SKU and day
CREATE TABLE reporting.trade_line_fact (
    line_id                uuid          NOT NULL,
    measure                text          NOT NULL CHECK (measure IN ('ACCEPTED', 'RECEIVED')),
    document_id            uuid          NOT NULL,
    owner_entity_id        uuid          NOT NULL,
    counterparty_entity_id uuid,
    seller_entity_id       uuid          NOT NULL,
    buyer_entity_id        uuid          NOT NULL,
    sku_id                 uuid          NOT NULL,
    business_date          date          NOT NULL,
    qty                    numeric(14,3) NOT NULL,
    value                  numeric(14,2),
    PRIMARY KEY (line_id, measure)
);

CREATE INDEX trade_line_fact_by_seller ON reporting.trade_line_fact (seller_entity_id, business_date);
CREATE INDEX trade_line_fact_by_buyer ON reporting.trade_line_fact (buyer_entity_id, business_date);

ALTER TABLE reporting.trade_line_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.trade_line_fact FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.trade_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON reporting.trade_line_fact FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON reporting.trade_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON reporting.trade_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.trade_line_fact FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.trade_line_fact TO app_rw;
