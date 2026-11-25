-- RPT-01: Project debit notes alongside invoices, credits and payments.
-- Debit note settlements increase the outstanding amount of an invoice.

ALTER TABLE reporting.trade_document_event
    DROP CONSTRAINT trade_document_event_doc_type_check;

ALTER TABLE reporting.trade_document_event
    ADD CONSTRAINT trade_document_event_doc_type_check
    CHECK (doc_type IN (
        'ORDER', 'DELIVERY_NOTE', 'GRN', 'INVOICE',
        'CREDIT_NOTE', 'DEBIT_NOTE', 'PAYMENT',
        'DISCREPANCY', 'CLAIM'
    ));

ALTER TABLE reporting.trade_settlement_fact
    DROP CONSTRAINT trade_settlement_fact_kind_check;

ALTER TABLE reporting.trade_settlement_fact
    ADD CONSTRAINT trade_settlement_fact_kind_check
    CHECK (kind IN ('PAYMENT', 'REVERSAL', 'CREDIT', 'DEBIT'));
