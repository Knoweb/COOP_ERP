-- The quantity at issue on a discrepancy (demo walkthrough, 29 September 2026: an open
-- discrepancy showed a blank amount in the exception queue). discrepancy.raised.v1 carries
-- quantities, not prices, so the queue shows the quantity: the sum over the lines of the
-- variance (short or over, without its sign) and the damaged quantity. Filled for rows projected
-- from now on; a row projected before stays null and shows nothing, as it did. The table's
-- policies are unchanged.
ALTER TABLE reporting.trade_document_event ADD COLUMN qty_at_issue numeric(14,3);
