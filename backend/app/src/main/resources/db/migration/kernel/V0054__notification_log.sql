-- K-10: the delivery record of notifications (doc 19 section 7; 19A section 10). Lane B's
-- table (notification_log, 19A section 10) in lane C's range: lane B is closed and the
-- ranges are per lane, not per table; the README says which numbers are taken.
--
-- Insert-plus-status-update only. The unique key (rule, event, recipient) is the idempotency:
-- a replayed event sends once. The recipient is a hash (a phone number never sits in a log),
-- the body is not stored beyond its length (ADR-27). owner_entity_id is the entity the
-- notification was raised for, so the entity reads its own log and the federation all of it.

CREATE TABLE kernel.notification_log (
    notification_id uuid PRIMARY KEY,
    rule_id uuid NOT NULL,
    event_id uuid NOT NULL,
    owner_entity_id uuid NOT NULL,
    recipient_hash char(64) NOT NULL,
    channel text NOT NULL
        CHECK (channel IN ('SMS', 'EMAIL', 'IN_APP')),
    template_id varchar(48),
    language char(2),
    rendered_length integer,
    status text NOT NULL
        CHECK (status IN ('QUEUED', 'SENT', 'FAILED', 'SUPPRESSED')),
    suppressed_reason text,
    provider_ref text,
    last_error text,
    attempts smallint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    next_attempt_at timestamptz,
    UNIQUE (rule_id, event_id, recipient_hash)
);

CREATE INDEX notification_log_retry_idx
    ON kernel.notification_log (next_attempt_at)
    WHERE status = 'QUEUED';

CREATE INDEX notification_log_dedup_idx
    ON kernel.notification_log (recipient_hash, template_id, created_at DESC);

ALTER TABLE kernel.notification_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.notification_log FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.notification_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON kernel.notification_log FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON kernel.notification_log FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.notification_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.notification_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.notification_log FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.notification_log TO app_rw;
GRANT UPDATE (status, suppressed_reason, provider_ref, last_error, attempts, last_attempt_at, next_attempt_at,
              rendered_length, language)
    ON kernel.notification_log TO app_rw;
