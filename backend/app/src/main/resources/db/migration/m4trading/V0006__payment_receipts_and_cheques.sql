-- M4-07 (29 September 2026): the buyer pays. The seller's accounts record a payment receipt (PRC,
-- the seller's ENTITY series), settle open invoices with it, and record a cheque's outcome; a
-- bounced cheque reverses the receipt. 24A sections 3 and 6.3; what differs and why is in the
-- module README ("Payments and exposure"). The exposure of M4-09 is computed on read from the
-- same rows (ExposureQueries), so trading.relationship_exposure is not created.
--
-- 1. trading.doc_payment_receipt: the receipt, an extension of its kernel document like
--    doc_invoice (it follows the header; the buyer reads it as the counterparty). A reversal is a
--    PRC of its own that names the receipt it reverses (reversal_of; the REVERSES link in
--    kernel.document_link says the same) and carries the same amount.
CREATE TABLE trading.doc_payment_receipt (
    document_id      uuid          PRIMARY KEY,
    relationship_id  uuid          NOT NULL,
    seller_entity_id uuid          NOT NULL,
    payer_entity_id  uuid          NOT NULL,
    method           text          NOT NULL CHECK (method IN ('CASH', 'CHEQUE', 'TRANSFER', 'DEPOSIT')),
    reference        text,
    received_on      date          NOT NULL,
    amount           numeric(14,2) NOT NULL CHECK (amount > 0),
    reversal_of      uuid,
    reason           text
);
CREATE INDEX doc_payment_receipt_pair ON trading.doc_payment_receipt (seller_entity_id, payer_entity_id);
CREATE UNIQUE INDEX doc_payment_receipt_reversal ON trading.doc_payment_receipt (reversal_of)
    WHERE reversal_of IS NOT NULL;

-- 2. trading.payment_allocation: what a receipt settled of each invoice, one insert-only row per
--    invoice. A reversal writes the negated rows under its own document, so an invoice's settled
--    amount is the sum of its rows and a bounced cheque reopens it. The amounts are kept here and
--    not as SETTLES links: kernel.document_link refuses a negative amount and counts every
--    SETTLES link against the open balance for ever, so a bounced receipt would block the next
--    payment of the same invoice (see the module README).
CREATE TABLE trading.payment_allocation (
    allocation_id       uuid          PRIMARY KEY,
    receipt_document_id uuid          NOT NULL,
    invoice_document_id uuid          NOT NULL,
    amount              numeric(14,2) NOT NULL CHECK (amount <> 0)
);
CREATE INDEX payment_allocation_receipt ON trading.payment_allocation (receipt_document_id);
CREATE INDEX payment_allocation_invoice ON trading.payment_allocation (invoice_document_id);

-- 3. trading.cheque: the cheque a receipt was paid by (24A section 3), written with the receipt.
CREATE TABLE trading.cheque (
    receipt_document_id uuid        PRIMARY KEY,
    bank                text        NOT NULL,
    cheque_no           varchar(20) NOT NULL,
    dated               date        NOT NULL
);

-- 4. trading.cheque_outcome: CLEARED or BOUNCED, once per cheque, insert-only (24A's
--    cheque.status column is kept as this row, so the receipt's extension rows are never
--    updated). The seller's own row, read by the buyer through party_read.
CREATE TABLE trading.cheque_outcome (
    receipt_document_id    uuid        PRIMARY KEY,
    outcome                text        NOT NULL CHECK (outcome IN ('CLEARED', 'BOUNCED')),
    reversal_document_id   uuid,
    reason                 text,
    recorded_by            uuid        NOT NULL,
    recorded_at            timestamptz NOT NULL,
    owner_entity_id        uuid        NOT NULL,
    counterparty_entity_id uuid        NOT NULL
);

-- Row-level security. (a) The rows of a document follow the header (V0001 (a)).
DO $$
DECLARE
    t text;
    k text;
BEGIN
    FOREACH t IN ARRAY ARRAY['doc_payment_receipt', 'payment_allocation', 'cheque']
    LOOP
        k := CASE WHEN t = 'doc_payment_receipt' THEN 'document_id' ELSE 'receipt_document_id' END;
        EXECUTE format('ALTER TABLE trading.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE trading.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY document_read ON trading.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.document_visible(%I))', t, k);
        EXECUTE format('CREATE POLICY document_write ON trading.%I FOR INSERT TO app_rw'
                       || ' WITH CHECK (kernel.document_owned(%I))', t, k);
        EXECUTE format('GRANT SELECT, INSERT ON trading.%I TO app_rw', t);
    END LOOP;
END;
$$;

-- (b) The seller's own row, on the template with party_read (V0005's discrepancy_settlement).
ALTER TABLE trading.cheque_outcome ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.cheque_outcome FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.cheque_outcome FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON trading.cheque_outcome FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON trading.cheque_outcome FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON trading.cheque_outcome FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.cheque_outcome FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.cheque_outcome TO app_rw;

-- 5. The invoice's settled_amount cache (V0001: "recomputed from the links by the handlers that
--    write them"): RecordPaymentReceipt and a bounced cheque write it on the seller's own invoice,
--    as the sum of its payment_allocation rows.
GRANT UPDATE (settled_amount) ON trading.doc_invoice TO app_rw;
