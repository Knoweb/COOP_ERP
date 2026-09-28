-- M4 finishing items (29 September 2026): an order amended before acceptance, money held on
-- account applied to later invoices, and the A4 print of a payment receipt. 24A sections 6 and
-- 6.3; what differs and why is in the module README ("Amendment, application and the receipt's
-- print").
--
-- 1. An amendment is a new order document, the next version of the one it replaces (AmendOrder):
--    an issued order's lines are never edited, and the kernel's SUPERSEDES link is for drafts
--    only, so the new order names the one it amends here. doc_order.version (V0001) carries the
--    version number.
ALTER TABLE trading.doc_order ADD COLUMN amends_order_id uuid;
CREATE UNIQUE INDEX doc_order_amends ON trading.doc_order (amends_order_id) WHERE amends_order_id IS NOT NULL;

-- 2. ApplyReceipt: the rows that apply money a receipt left on account to later invoices are
--    ordinary allocation rows of that receipt (so a bounced cheque's reversal negates them with
--    the rest), marked with the application that wrote them.
ALTER TABLE trading.payment_allocation ADD COLUMN application_id uuid;

-- 3. The worker keeps where the printed A4 copy of a receipt is, as for the invoice (V0003) and
--    the credit note (V0005).
ALTER TABLE trading.doc_payment_receipt ADD COLUMN print_object_key text;
CREATE POLICY document_update ON trading.doc_payment_receipt FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT UPDATE (print_object_key) ON trading.doc_payment_receipt TO app_rw;
