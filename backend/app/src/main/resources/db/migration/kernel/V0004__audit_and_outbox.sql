CREATE SCHEMA IF NOT EXISTS kernel;


-- =========================================================
-- Durable audit log
-- =========================================================

CREATE TABLE kernel.audit_log (
    audit_id uuid PRIMARY KEY,
    event_type varchar(160) NOT NULL,

    subject_json jsonb NOT NULL,

    before_json jsonb,
    after_json jsonb,

    scope_json jsonb,

    reason text,
    witness_user_id uuid,

    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_kernel_audit_log_event_type
    ON kernel.audit_log (event_type);

CREATE INDEX ix_kernel_audit_log_created_at
    ON kernel.audit_log (created_at);


-- =========================================================
-- Transactional outbox
-- =========================================================

CREATE TABLE kernel.outbox_event (
    event_id uuid PRIMARY KEY,
    event_type varchar(200) NOT NULL,
    payload jsonb NOT NULL,

    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX ix_kernel_outbox_unpublished
    ON kernel.outbox_event (created_at)
    WHERE published_at IS NULL;


GRANT SELECT, INSERT
    ON kernel.audit_log
    TO app_rw;

GRANT SELECT, INSERT, UPDATE
    ON kernel.outbox_event
    TO app_rw;