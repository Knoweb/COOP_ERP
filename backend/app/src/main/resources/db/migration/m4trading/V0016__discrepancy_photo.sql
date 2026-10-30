-- ORD-10: Allow photographs on a delivery discrepancy (doc 24 section 3.4; 19A section 9).
-- Evidence of variance (damaged or missing goods) captured by the receiver on the discrepancy.
CREATE TABLE trading.discrepancy_photo (
    attachment_id           uuid        PRIMARY KEY,
    discrepancy_document_id uuid        NOT NULL,
    added_by                uuid        NOT NULL,
    added_at                timestamptz NOT NULL
);
CREATE INDEX discrepancy_photo_discrepancy ON trading.discrepancy_photo (discrepancy_document_id);

ALTER TABLE trading.discrepancy_photo ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.discrepancy_photo FORCE ROW LEVEL SECURITY;
CREATE POLICY document_read ON trading.discrepancy_photo FOR SELECT TO app_rw
    USING (kernel.document_visible(discrepancy_document_id));
CREATE POLICY document_write ON trading.discrepancy_photo FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(discrepancy_document_id));
GRANT SELECT, INSERT ON trading.discrepancy_photo TO app_rw;
