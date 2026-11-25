-- M9 Integration: Central Notification Gateway and Message Alerts (PGL-M3-02).
-- Table for tracking notification requests, statuses (PENDING, SENT, FAILED), provider references,
-- delivery attempts, and idempotency deduplication keys.

CREATE TABLE integration.notification_request (
    request_id         uuid        PRIMARY KEY,
    owner_entity_id    uuid        NOT NULL,
    channel            text        NOT NULL CHECK (channel IN ('SMS', 'EMAIL', 'MESSAGE')),
    recipient          text        NOT NULL CHECK (btrim(recipient) <> ''),
    recipient_tag      text        NOT NULL,
    message_type       text        NOT NULL,
    content            text        NOT NULL,
    status             text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts           int         NOT NULL DEFAULT 0,
    provider           text        NOT NULL,
    provider_ref       text,
    last_error         text,
    dedup_key          text        NOT NULL,
    metadata           jsonb       NOT NULL DEFAULT '{}'::jsonb,
    next_attempt_at    timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT notification_request_owner_dedup_uq UNIQUE (owner_entity_id, dedup_key)
);

CREATE INDEX notification_request_owner_status ON integration.notification_request (owner_entity_id, status);
CREATE INDEX notification_request_pending ON integration.notification_request (status, next_attempt_at) WHERE status = 'PENDING';

ALTER TABLE integration.notification_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.notification_request FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.notification_request FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.notification_request FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON integration.notification_request FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.notification_request FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.notification_request FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT, UPDATE ON integration.notification_request TO app_rw;

