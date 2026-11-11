-- ACC-10: E-invoicing readiness seam (M9-06).
-- Phase 1 uses a stub adapter; no external submission occurs.

CREATE TABLE integration.e_invoice_submission (
    invoice_document_id uuid PRIMARY KEY,
    owner_entity_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (status IN (
            'NOT_SUBMITTED',
            'PENDING',
            'SUBMITTED',
            'ACCEPTED',
            'REJECTED'
        )),
    provider_ref text,
    submitted_at timestamptz,
    response_key text,
    attempts smallint NOT NULL DEFAULT 0
        CHECK (attempts >= 0),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX e_invoice_submission_owner_status
    ON integration.e_invoice_submission
    (owner_entity_id, status);

ALTER TABLE integration.e_invoice_submission
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE integration.e_invoice_submission
    FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read
    ON integration.e_invoice_submission
    FOR SELECT TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_write
    ON integration.e_invoice_submission
    FOR INSERT TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_update
    ON integration.e_invoice_submission
    FOR UPDATE TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY fed_view
    ON integration.e_invoice_submission
    FOR SELECT TO app_rw
    USING (
        kernel.scope_class() = 'FEDERATION_VIEW'
    );

CREATE POLICY ext_view
    ON integration.e_invoice_submission
    FOR SELECT TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (
            kernel.granted_entities()
        )
    );

GRANT SELECT, INSERT, UPDATE
    ON integration.e_invoice_submission TO app_rw;
