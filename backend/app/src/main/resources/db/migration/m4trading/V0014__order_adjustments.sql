-- PRC-10 / M3-07: order adjustments (doc 24 §3.1; doc 24A §3.1, §6; doc 23 §3.2; doc 18 §4.8).
-- Manual adjustments (SPECIAL_DISCOUNT, DELIVERY_FEE) recorded by the seller on accepted orders,
-- applied when the tax invoice is issued from confirmed GRNs.

CREATE TABLE trading.doc_order_adjustment (
    adjustment_id          uuid          PRIMARY KEY,
    order_id               uuid          NOT NULL,
    owner_entity_id        uuid          NOT NULL,
    counterparty_entity_id uuid          NOT NULL,
    adjustment_type        varchar(24)   NOT NULL,
    kind                   text          NOT NULL CHECK (kind IN ('SPECIAL_DISCOUNT', 'DELIVERY_FEE')),
    amount                 numeric(14,2) NOT NULL CHECK (amount > 0),
    description            text,
    recorded_by            uuid          NOT NULL,
    recorded_at            timestamptz   NOT NULL DEFAULT now(),
    invoice_document_id    uuid,
    CONSTRAINT uq_order_adjustment UNIQUE (order_id, adjustment_type)
);

CREATE INDEX doc_order_adjustment_order ON trading.doc_order_adjustment (order_id);
CREATE INDEX doc_order_adjustment_invoice ON trading.doc_order_adjustment (invoice_document_id) WHERE invoice_document_id IS NOT NULL;

ALTER TABLE trading.doc_order_adjustment ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.doc_order_adjustment FORCE ROW LEVEL SECURITY;

CREATE POLICY party_read ON trading.doc_order_adjustment FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));

CREATE POLICY own_write ON trading.doc_order_adjustment FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_update ON trading.doc_order_adjustment FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY fed_view ON trading.doc_order_adjustment FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON trading.doc_order_adjustment FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON trading.doc_order_adjustment TO app_rw;
GRANT UPDATE (invoice_document_id) ON trading.doc_order_adjustment TO app_rw;
