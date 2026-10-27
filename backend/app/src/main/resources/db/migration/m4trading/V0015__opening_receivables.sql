-- CAT-12: inter-entity receivable opening balances (bulk onboarding).
-- The two-party signed opening receivable document links to kernel.document and trading.doc_invoice,
-- recording the authorization from both the seller (e.g. Distributor) and the buyer (e.g. MPCS).

CREATE TABLE trading.opening_receivable (
    opening_receivable_id uuid          PRIMARY KEY,
    document_id           uuid          NOT NULL UNIQUE,
    relationship_id       uuid          NOT NULL,
    seller_entity_id      uuid          NOT NULL,
    buyer_entity_id       uuid          NOT NULL,
    amount                numeric(14,2) NOT NULL CHECK (amount > 0),
    reference_no          varchar(60),
    seller_signed_by      uuid          NOT NULL,
    seller_signed_at      timestamptz   NOT NULL DEFAULT now(),
    buyer_signed_by       uuid          NOT NULL,
    buyer_signed_at       timestamptz   NOT NULL DEFAULT now(),
    notes                 text,
    created_at            timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX opening_receivable_parties ON trading.opening_receivable (seller_entity_id, buyer_entity_id);
CREATE INDEX opening_receivable_document ON trading.opening_receivable (document_id);

ALTER TABLE trading.opening_receivable ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.opening_receivable FORCE ROW LEVEL SECURITY;

CREATE POLICY document_read ON trading.opening_receivable FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));

CREATE POLICY document_write ON trading.opening_receivable FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_open_for_write(document_id));

GRANT SELECT, INSERT ON trading.opening_receivable TO app_rw;
