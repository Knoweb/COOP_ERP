-- Wave 3, M4-09: V0012's v_invoice_line_party kept only the lines of documents typed 'INVOICE',
-- a code no document type has (invoices are 'INV', seed/kernel/document-types.yaml), so a PARTY
-- session read every invoice with no lines. The view now keeps the lines of the documents that
-- have an invoice row, whatever the type code is called: the same join v_invoice_party makes.
-- Same columns in the same order, so CREATE OR REPLACE keeps the grant to app_rw.
CREATE OR REPLACE VIEW trading.v_invoice_line_party WITH (security_invoker = true) AS
SELECT
    l.document_line_id AS line_id,
    l.document_id,
    l.line_no,
    l.sku_id,
    l.batch_id,
    l.uom_code,
    l.qty,
    l.unit_price,
    l.tax_rate_percent,
    l.tax_amount,
    l.line_total,
    l.reference_line_id
FROM kernel.document_line l
JOIN trading.doc_invoice i ON i.document_id = l.document_id;
