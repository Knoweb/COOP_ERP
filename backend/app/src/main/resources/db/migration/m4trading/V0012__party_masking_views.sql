CREATE VIEW trading.v_invoice_party WITH (security_invoker = true) AS
SELECT
    d.document_id,
    d.doc_type_code,
    d.series_id,
    d.doc_number,
    d.doc_number_display,
    d.owner_entity_id,
    d.counterparty_entity_id,
    d.location_id,
    d.till_position_id,
    d.device_id,
    d.status,
    d.issued_at,
    d.issued_local,
    d.business_date,
    d.operator_user_id,
    d.currency,
    d.net_amount,
    d.tax_amount,
    d.gross_amount,
    d.reference_document_id,
    d.content_hash,
    d.origin,
    d.device_seq,

    i.relationship_id,
    i.seller_entity_id,
    i.buyer_entity_id,
    i.grn_document_ids,
    i.delivery_document_id,
    i.seller_vat_no,
    i.buyer_vat_no,
    i.tax_point_date,
    i.due_date,
    i.settled_amount,
    i.credited_amount,
    i.e_invoice_status,
    i.dispute_reason
FROM trading.doc_invoice i
JOIN kernel.document d ON i.document_id = d.document_id;

GRANT SELECT ON trading.v_invoice_party TO app_rw;

CREATE VIEW trading.v_invoice_line_party WITH (security_invoker = true) AS
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
JOIN kernel.document d ON l.document_id = d.document_id
WHERE d.doc_type_code = 'INVOICE';

GRANT SELECT ON trading.v_invoice_line_party TO app_rw;
