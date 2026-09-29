-- M4-06 (29 September 2026): the claims on trade_document_event. claim.raised.v1 is a CLAIM row
-- RAISED, claim.approved.v1 APPROVED and claim.rejected.v1 REJECTED; the exception queue shows a
-- claim raised and not decided (CLAIM_OPEN, REVIEW), as it shows an open discrepancy (28A section
-- 7). The table's policies are unchanged.
ALTER TABLE reporting.trade_document_event DROP CONSTRAINT trade_document_event_event_kind_check;
ALTER TABLE reporting.trade_document_event ADD CONSTRAINT trade_document_event_event_kind_check CHECK (event_kind IN
    ('SUBMITTED', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'DISPATCHED', 'CONFIRMED', 'ISSUED',
     'RAISED', 'SETTLED', 'DISPUTED', 'DISPUTE_RESOLVED', 'RECORDED', 'REVERSED', 'BOUNCED', 'CLEARED',
     'APPROVED'));
ALTER TABLE reporting.trade_document_event DROP CONSTRAINT trade_document_event_doc_type_check;
ALTER TABLE reporting.trade_document_event ADD CONSTRAINT trade_document_event_doc_type_check CHECK (doc_type IN
    ('ORDER', 'DELIVERY_NOTE', 'GRN', 'INVOICE', 'CREDIT_NOTE', 'PAYMENT', 'DISCREPANCY', 'CLAIM'));
