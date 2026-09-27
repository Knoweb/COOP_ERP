-- M4-11 (demo scope, 27 September 2026): where the printed invoice is.
--
-- The worker prints an issued invoice to A4 (M4-08, InvoicePrintConsumer, K-06b's A4Renderer)
-- and the PDF lands in the object store under the seller. The invoice screen's Print button needs
-- to find it again, so RecordInvoicePrint (the consumer's command, audited INVOICE_PRINTED) keeps
-- its object key on the seller's invoice row; the web role hands out a fresh pre-signed link from
-- it (A4Renderer.presignGet). Only the owner updates its row, like every extension table.
ALTER TABLE trading.doc_invoice ADD COLUMN print_object_key text;

CREATE POLICY document_update ON trading.doc_invoice FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT UPDATE (print_object_key) ON trading.doc_invoice TO app_rw;
