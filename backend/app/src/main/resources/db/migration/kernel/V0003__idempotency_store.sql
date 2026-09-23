CREATE SCHEMA kernel;

CREATE TABLE kernel.idempotency_key (
    user_id uuid NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response_status integer NOT NULL,
    response_body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT (now() + interval '24 hours'),

    CONSTRAINT pk_kernel_idempotency_key
        PRIMARY KEY (user_id, idempotency_key)
);

CREATE INDEX ix_kernel_idempotency_expiry
    ON kernel.idempotency_key (expires_at);

GRANT SELECT, INSERT, UPDATE, DELETE
    ON kernel.idempotency_key
    TO app_rw;