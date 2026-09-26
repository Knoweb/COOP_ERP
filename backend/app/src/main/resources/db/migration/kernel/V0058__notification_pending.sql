-- K-10 review (26 Sep): what a retry needs, where every instance reads it. Lane C, above V0054:
-- the table references notification_log, which lane B put at V0054, so a lane B number would
-- run before its parent exists on a fresh database.
--
-- The log (V0054) holds a hash of the recipient and never the body (doc 19 section 7, ADR-27),
-- so a retry could not be sent by any instance but the one that queued the notification, and
-- after a restart by nobody. This table holds the recipient and the placeholders of a QUEUED
-- notification until it is SENT, SUPPRESSED or FAILED (at most 24 hours, doc 19: "never
-- retried past 24 hours"), then the row is cleared: the recipient and the arguments are set
-- to nothing and cleared_at says when. The row is never deleted (17A section 6.2: no DELETE for
-- app_rw) and it is not the log: the log stays hashed.
--
-- Only the owner reads and writes its rows (own_* of the template, no location column): the
-- sweep finds the due rows in the log as the federation viewer and then works on each in the
-- OWN scope of its entity, so a federation-wide or a granted reader never sees a number.

CREATE TABLE kernel.notification_pending (
    notification_id uuid PRIMARY KEY REFERENCES kernel.notification_log (notification_id),
    owner_entity_id uuid NOT NULL,
    recipient text,
    template_id varchar(48),
    language char(2),
    arguments jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    cleared_at timestamptz
);

ALTER TABLE kernel.notification_pending ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.notification_pending FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.notification_pending FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON kernel.notification_pending FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON kernel.notification_pending FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

REVOKE ALL PRIVILEGES ON kernel.notification_pending FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.notification_pending TO app_rw;
GRANT UPDATE (recipient, arguments, cleared_at) ON kernel.notification_pending TO app_rw;
