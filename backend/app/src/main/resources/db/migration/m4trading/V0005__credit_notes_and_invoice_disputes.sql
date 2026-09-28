-- M4-08, the rest the demo needs (28 September 2026): a short delivery is settled with a credit
-- note, and a buyer may dispute an invoice. 24A sections 3 and 6; what differs and why is in the
-- module README ("Credit notes and invoice disputes").
--
-- 1. trading.doc_credit_note: the seller's credit note (CN, doc 24 section 3.6), an extension of
--    its kernel document like doc_invoice, so it follows the header (document_read /
--    document_write; the buyer reads it as the counterparty). It names the invoice it credits
--    (the CREDITS link in kernel.document_link carries the amount) and, when it settles one, the
--    buyer's discrepancy. The discrepancy is the buyer's document and the seller never writes it
--    (AGENTS.md idea 3), so "settled" is read from here, not stored on the discrepancy: at most
--    one credit note settles a discrepancy (the unique index).
CREATE TABLE trading.doc_credit_note (
    document_id             uuid PRIMARY KEY,
    invoice_document_id     uuid NOT NULL,
    discrepancy_document_id uuid,
    reason                  text NOT NULL,
    print_object_key        text
);
CREATE INDEX doc_credit_note_invoice ON trading.doc_credit_note (invoice_document_id);
CREATE UNIQUE INDEX doc_credit_note_discrepancy ON trading.doc_credit_note (discrepancy_document_id)
    WHERE discrepancy_document_id IS NOT NULL;

ALTER TABLE trading.doc_credit_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.doc_credit_note FORCE ROW LEVEL SECURITY;
CREATE POLICY document_read ON trading.doc_credit_note FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));
CREATE POLICY document_write ON trading.doc_credit_note FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id));
-- The worker keeps where the printed A4 copy is, as for the invoice (V0003).
CREATE POLICY document_update ON trading.doc_credit_note FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT SELECT, INSERT ON trading.doc_credit_note TO app_rw;
GRANT UPDATE (print_object_key) ON trading.doc_credit_note TO app_rw;

-- 2. The invoice's credited_amount cache (V0001: "recomputed from document_link by the handlers
--    that write the links"): IssueCreditNote writes it on the seller's own invoice.
GRANT UPDATE (credited_amount) ON trading.doc_invoice TO app_rw;

-- 3. trading.invoice_dispute: the dispute of an invoice, one insert-only row per act. The invoice
--    is the seller's document and the buyer who disputes it may not write it (AGENTS.md idea 3),
--    so doc_invoice.dispute_reason and a DISPUTED status on the invoice stay unused: the buyer
--    records DISPUTED in a row it owns, either party records RESOLVED in a row it owns, and the
--    invoice is disputed while its latest row says DISPUTED. The template with party_read: owner
--    and counterparty both read every row of their invoices.
CREATE TABLE trading.invoice_dispute (
    dispute_event_id       uuid        PRIMARY KEY,
    invoice_document_id    uuid        NOT NULL,
    action                 text        NOT NULL CHECK (action IN ('DISPUTED', 'RESOLVED')),
    reason                 text,
    actor_user_id          uuid        NOT NULL,
    recorded_at            timestamptz NOT NULL,
    owner_entity_id        uuid        NOT NULL,
    counterparty_entity_id uuid        NOT NULL
);
CREATE INDEX invoice_dispute_invoice ON trading.invoice_dispute (invoice_document_id, recorded_at);

ALTER TABLE trading.invoice_dispute ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.invoice_dispute FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.invoice_dispute FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON trading.invoice_dispute FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON trading.invoice_dispute FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON trading.invoice_dispute FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.invoice_dispute FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.invoice_dispute TO app_rw;
