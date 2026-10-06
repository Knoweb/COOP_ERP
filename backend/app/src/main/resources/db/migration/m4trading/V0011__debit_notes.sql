-- M4 debit notes

CREATE TABLE trading.doc_debit_note (
    document_id             uuid PRIMARY KEY,
    invoice_document_id     uuid NOT NULL,
    reason                  text NOT NULL,
    print_object_key        text
);
CREATE INDEX doc_debit_note_invoice ON trading.doc_debit_note (invoice_document_id);

ALTER TABLE trading.doc_debit_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.doc_debit_note FORCE ROW LEVEL SECURITY;
CREATE POLICY document_read ON trading.doc_debit_note FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));
CREATE POLICY document_write ON trading.doc_debit_note FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
CREATE POLICY document_update ON trading.doc_debit_note FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT SELECT, INSERT ON trading.doc_debit_note TO app_rw;
GRANT UPDATE (print_object_key) ON trading.doc_debit_note TO app_rw;

ALTER TABLE trading.doc_invoice ADD COLUMN debited_amount numeric(14,2) NOT NULL DEFAULT 0;
GRANT UPDATE (debited_amount) ON trading.doc_invoice TO app_rw;
