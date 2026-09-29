-- M4-10 (29 September 2026): a transfer issued for a shop's transfer request names the request
-- (24A section 7, "m4.transfer-xfr: on M5's xfr.issued.v1 write back xfr_document_id"). The link
-- is kept on M5's own issue row, written once when the transfer is issued, and M4 reads it back
-- through InventoryQueries.transferOfRequest instead of writing it into its request (module
-- README of m4trading, "Transfer requests"). At most one transfer per request.
ALTER TABLE inventory.transfer ADD COLUMN transfer_request_id uuid;
CREATE UNIQUE INDEX transfer_by_request ON inventory.transfer (transfer_request_id)
    WHERE transfer_request_id IS NOT NULL;
