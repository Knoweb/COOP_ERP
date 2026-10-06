-- Wave 2 of the code review (RLS-09, RLS-11), decided 6 October 2026 on the architect's delegation:
-- docs/progress/deviations/2026-10-06-wave2-extension-rows-after-issue.md (1) to (3).

-- ---- 1. an extension row is written before its document is issued, or in the issuing transaction --

-- The rule (RLS_POLICY_TEMPLATE.md, "The rows of a document"): an extension row of a document is
-- written before the document is issued or in the transaction that issues it, never later; what a
-- handler specification writes after issue is a column named in a grant, under its own
-- document_update policy (doc_order_line.cancelled_qty, the delivery's dispatch columns and the
-- drop's proof of delivery, doc_invoice's settled_amount and credited_amount caches, the three
-- print_object_keys), unchanged here. kernel.document_open_for_write (kernel V0086) is true for an
-- unissued header and for a header issued in the current transaction, so the nine handlers that
-- issue first and then write their row in the same transaction (ConfirmGrn, its discrepancy,
-- IssueInvoice, IssueCreditNote, SettleDiscrepancy, ApproveClaim, RaiseClaim, RecordPaymentReceipt,
-- RecordChequeOutcome) still pass, and CaptureGrn, CreateOrder, AmendOrder and CreateDeliveryNote
-- write before issue. Out of scope, as they are not extension rows: trading.claim_photo (photographs
-- may be added to an issued claim, as kernel.document_attachment admits attachments), and the
-- other party's own rows or insert-only facts about an issued document (payment_allocation, cheque,
-- cheque_outcome, invoice_dispute, claim_decision and its lines, discrepancy_settlement, claim_return).
ALTER POLICY document_write ON trading.doc_order
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_order_line
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_delivery
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_delivery_drop
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_delivery_line
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_grn
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_grn_line
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_discrepancy
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_discrepancy_line
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_invoice
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_credit_note
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_payment_receipt
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_claim
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_write ON trading.doc_claim_line
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));

-- The GRN's own updates (the batch and the cost on its lines, who confirmed it and when) are made by
-- ConfirmGrn in the confirming transaction, and never after: nothing on a confirmed GRN is edited
-- (AGENTS.md idea 2).
ALTER POLICY document_update ON trading.doc_grn
    USING (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id))
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));
ALTER POLICY document_update ON trading.doc_grn_line
    USING (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id))
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));

-- A GRN line is money and stock, so the rule holds for every role, the migrator and a superuser
-- included, as kernel V0055 made it hold for kernel.document_line: a trigger refuses a line under a
-- GRN issued in an earlier transaction. SECURITY INVOKER (the default): it reads the header as the
-- caller does; for the application user the policy above refuses the row as well.
CREATE OR REPLACE FUNCTION trading.grn_line_after_issue()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, kernel, pg_temp
AS $$
BEGIN
    IF EXISTS (SELECT 1
                 FROM kernel.document d
                WHERE d.document_id = NEW.document_id
                  AND d.issued_at IS NOT NULL
                  AND d.xmin <> pg_current_xact_id()::xid) THEN
        RAISE EXCEPTION 'document.immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION trading.grn_line_after_issue() FROM PUBLIC;

CREATE TRIGGER trg_grn_line_after_issue
    BEFORE INSERT ON trading.doc_grn_line
    FOR EACH ROW EXECUTE FUNCTION trading.grn_line_after_issue();

-- ---- 2. a shop-scoped PARTY session reads the returns of its own shop (RLS-11) --------------------

-- The template's PARTY branch applies the location line on the owner's side (CR-17A-3; kernel.document
-- V0055 item 4): a PARTY session at a shop reads its entity's returns sent from that shop, and every
-- return sent to its entity. The OWN branch's narrowing stays as V0008 wrote it (an OWN session reads
-- here only the returns sent to it; its own through own_read, which keeps a shop to its location).
DROP POLICY party_read ON trading.claim_return;
CREATE POLICY party_read ON trading.claim_return FOR SELECT TO app_rw
    USING ((kernel.scope_class() = 'PARTY'
            AND ((owner_entity_id = kernel.scope_entity()
                  AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                 OR counterparty_entity_id = kernel.scope_entity()))
           OR (kernel.scope_class() = 'OWN' AND counterparty_entity_id = kernel.scope_entity()));
