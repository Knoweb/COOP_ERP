-- K-08, first part: the edge sync gateway of doc 32 as 19A section 8 restates it. Lane D
-- (V0080 to V0099, kernel/README.md).
--
--   device_sync_cursor         the only server-side sync state of a device (doc 32 section 2, S7):
--                              the highest applied sequence, the last batch and its
--                              acknowledgement, the batch in flight; row-locked by ingestion
--   sync_event                 one row per sequence number a device sent, applied or quarantined:
--                              what makes a resend answer from state (S3) and finds an event id
--                              seen at another sequence (doc 32 section 7)
--   sync_quarantine            a fact central could not accept, kept raw with its reason, never
--                              lost and never blocking what follows (S4)
--   device_heartbeat           the latest health report of each device (doc 32 section 6)
--   device_enrolment_code      one-time codes an administrator issues and a device spends once
--   location_snapshot_version  one monotonic snapshot version per location (doc 32 section 5.2)
--   change_log                 what changed in a location's snapshot, per version: the rows the
--                              snapshot delta is built from, written through kernel.api.ChangeLog
--
-- M1 owns the devices (party.device); the kernel reads that table and writes none of M1's.
--
-- The cursor, the ledger, the heartbeat and the codes carry the device's entity and not its
-- location: a device keeps its sequence when M1 moves it to another shop, and a policy with the
-- location line would hide its own cursor from it there. The quarantine keeps the location the
-- fact came from, for the shop's review.

CREATE TABLE kernel.device_sync_cursor (
    device_id uuid PRIMARY KEY,
    owner_entity_id uuid NOT NULL,
    last_applied_seq bigint NOT NULL DEFAULT 0
        CHECK (last_applied_seq >= 0),
    last_applied_at timestamptz,
    last_batch_id uuid,
    -- The acknowledgement of last_batch_id, so a lost acknowledgement is answered exactly (19A).
    last_ack jsonb,
    last_ack_at timestamptz,
    -- One batch in flight per device on any instance (doc 32 section 3.2): set when a batch
    -- claims the cursor, cleared when it completes; a claim older than
    -- sync.batch.in_flight_timeout is a crashed instance's and may be taken over.
    in_flight_batch_id uuid,
    in_flight_since timestamptz,
    -- Consecutive 409 sync.sequence_gap answers; SYNC_ANOMALY when they repeat (doc 32 section 7).
    gap_rejections integer NOT NULL DEFAULT 0,
    -- Set when the device reported more acknowledged than central holds (central restored from
    -- backup): the acknowledgements ask for a resend from here until the cursor passes it.
    resend_from_seq bigint,
    snapshot_version_reported bigint,
    app_version varchar(20),
    credential_client_id text,
    enrolled_at timestamptz NOT NULL DEFAULT now(),
    last_enrolled_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE kernel.device_sync_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.device_sync_cursor FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.device_sync_cursor FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON kernel.device_sync_cursor FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
-- The cursor is the one sync row updated in place (doc 32 section 2): by its own entity.
CREATE POLICY own_update ON kernel.device_sync_cursor FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.device_sync_cursor FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.device_sync_cursor FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.device_sync_cursor FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.device_sync_cursor TO app_rw;
GRANT UPDATE (last_applied_seq, last_applied_at, last_batch_id, last_ack, last_ack_at, in_flight_batch_id,
              in_flight_since, gap_rejections, resend_from_seq, snapshot_version_reported, app_version,
              credential_client_id, last_enrolled_at)
    ON kernel.device_sync_cursor TO app_rw;


CREATE TABLE kernel.sync_event (
    device_id uuid NOT NULL,
    device_seq bigint NOT NULL
        CHECK (device_seq >= 1),
    owner_entity_id uuid NOT NULL,
    event_id uuid,
    event_type text,
    batch_id uuid NOT NULL,
    outcome text NOT NULL
        CHECK (outcome IN ('APPLIED', 'QUARANTINED')),
    reason text,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, device_seq)
);

-- An event id is applied once, whichever device and sequence it arrives with (doc 32 S3).
CREATE UNIQUE INDEX sync_event_applied_once
    ON kernel.sync_event (event_id)
    WHERE outcome = 'APPLIED';

CREATE INDEX sync_event_by_event_id
    ON kernel.sync_event (event_id);

ALTER TABLE kernel.sync_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.sync_event FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.sync_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON kernel.sync_event FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.sync_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.sync_event FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

-- Insert-only, like every ledger (AGENTS.md).
REVOKE ALL PRIVILEGES ON kernel.sync_event FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.sync_event TO app_rw;


CREATE TABLE kernel.sync_quarantine (
    quarantine_id uuid PRIMARY KEY,
    device_id uuid NOT NULL,
    owner_entity_id uuid NOT NULL,
    location_id uuid,
    batch_id uuid NOT NULL,
    device_seq bigint NOT NULL,
    event_id uuid,
    event_type text,
    reason text NOT NULL
        CHECK (reason IN ('SCHEMA', 'DUPLICATE_ID', 'HASH', 'TOO_LARGE', 'FORBIDDEN_FIELD')),
    detail text,
    -- The event as it arrived, as JSON text: never parsed again by anything but a person.
    raw_event text NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX sync_quarantine_device_idx
    ON kernel.sync_quarantine (device_id, device_seq);

ALTER TABLE kernel.sync_quarantine ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.sync_quarantine FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.sync_quarantine FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON kernel.sync_quarantine FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.sync_quarantine FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.sync_quarantine FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.sync_quarantine FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.sync_quarantine TO app_rw;


CREATE TABLE kernel.device_heartbeat (
    device_id uuid PRIMARY KEY,
    owner_entity_id uuid NOT NULL,
    last_seen_at timestamptz NOT NULL,
    app_version varchar(20) NOT NULL,
    snapshot_version bigint,
    pending_event_count integer,
    oldest_pending_seq_age_s bigint,
    last_acknowledged_seq bigint,
    battery_pct smallint,
    storage_free_mb bigint,
    peripherals jsonb,
    device_clock timestamptz,
    device_uptime_s bigint,
    open_session boolean,
    clock_offset_ms bigint
);

ALTER TABLE kernel.device_heartbeat ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.device_heartbeat FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.device_heartbeat FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON kernel.device_heartbeat FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON kernel.device_heartbeat FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.device_heartbeat FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.device_heartbeat FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.device_heartbeat FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.device_heartbeat TO app_rw;
GRANT UPDATE (last_seen_at, app_version, snapshot_version, pending_event_count, oldest_pending_seq_age_s,
              last_acknowledged_seq, battery_pct, storage_free_mb, peripherals, device_clock, device_uptime_s,
              open_session, clock_offset_ms)
    ON kernel.device_heartbeat TO app_rw;


CREATE TABLE kernel.device_enrolment_code (
    enrolment_code_id uuid PRIMARY KEY,
    device_id uuid NOT NULL,
    owner_entity_id uuid NOT NULL,
    -- SHA-256 of the code; the code itself is shown once and never stored.
    code_hash char(64) NOT NULL,
    issued_by_user_id uuid,
    issued_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    withdrawn_at timestamptz
);

CREATE INDEX device_enrolment_code_open_idx
    ON kernel.device_enrolment_code (device_id)
    WHERE used_at IS NULL AND withdrawn_at IS NULL;

ALTER TABLE kernel.device_enrolment_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.device_enrolment_code FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.device_enrolment_code FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON kernel.device_enrolment_code FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON kernel.device_enrolment_code FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.device_enrolment_code FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.device_enrolment_code FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.device_enrolment_code FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.device_enrolment_code TO app_rw;
GRANT UPDATE (used_at, withdrawn_at) ON kernel.device_enrolment_code TO app_rw;


-- The snapshot version of each location and its change log. Written only through
-- kernel.change_log_append below: a central publication (a shared SKU, a control price) changes
-- the snapshots of shops of many entities, and the module that publishes it acts in its own
-- entity's scope. The function runs as the migrator, a member of app_seed (kernel V0005), the
-- group that writes rows no single tenant's scope could; app_rw gets no INSERT at all, so the
-- function is the only way in. Reads follow the template: a device reads its own location's.

CREATE TABLE kernel.location_snapshot_version (
    location_id uuid PRIMARY KEY,
    owner_entity_id uuid NOT NULL,
    current_version bigint NOT NULL DEFAULT 0
        CHECK (current_version >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE kernel.location_snapshot_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.location_snapshot_version FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.location_snapshot_version FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON kernel.location_snapshot_version FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.location_snapshot_version FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY change_log_write ON kernel.location_snapshot_version
    FOR ALL TO app_seed USING (true) WITH CHECK (true);

REVOKE ALL PRIVILEGES ON kernel.location_snapshot_version FROM PUBLIC;
GRANT SELECT ON kernel.location_snapshot_version TO app_rw;
GRANT SELECT, INSERT, UPDATE ON kernel.location_snapshot_version TO app_seed;


CREATE TABLE kernel.change_log (
    location_id uuid NOT NULL,
    version bigint NOT NULL
        CHECK (version >= 1),
    owner_entity_id uuid NOT NULL,
    table_name varchar(48) NOT NULL,
    row_id uuid NOT NULL,
    op text NOT NULL
        CHECK (op IN ('UPSERT', 'DELETE')),
    apply_from date,
    urgent boolean NOT NULL DEFAULT false,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (location_id, version, table_name, row_id)
);

CREATE INDEX change_log_recorded_idx
    ON kernel.change_log (location_id, recorded_at);

ALTER TABLE kernel.change_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.change_log FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.change_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON kernel.change_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.change_log FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));
CREATE POLICY change_log_write ON kernel.change_log
    FOR INSERT TO app_seed WITH CHECK (true);
CREATE POLICY change_log_read ON kernel.change_log
    FOR SELECT TO app_seed USING (true);

REVOKE ALL PRIVILEGES ON kernel.change_log FROM PUBLIC;
GRANT SELECT ON kernel.change_log TO app_rw;
GRANT SELECT, INSERT ON kernel.change_log TO app_seed;


-- Records one publication in the snapshot of one location: bumps the location's version by one
-- and writes one change-log row per changed row under that version, in the caller's
-- transaction. The row lock on the version row orders two publications for one shop; two shops
-- never wait for each other.
CREATE OR REPLACE FUNCTION kernel.change_log_append(
    p_owner_entity_id uuid,
    p_location_id uuid,
    p_table_names text[],
    p_row_ids uuid[],
    p_ops text[],
    p_apply_from date,
    p_urgent boolean
)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, kernel
AS $$
DECLARE
    new_version bigint;
BEGIN
    IF p_owner_entity_id IS NULL OR p_location_id IS NULL THEN
        RAISE EXCEPTION 'change_log_append needs an owner entity and a location';
    END IF;
    IF coalesce(array_length(p_row_ids, 1), 0) = 0
       OR array_length(p_row_ids, 1) <> array_length(p_table_names, 1)
       OR array_length(p_row_ids, 1) <> array_length(p_ops, 1) THEN
        RAISE EXCEPTION 'change_log_append needs as many tables and operations as rows';
    END IF;

    INSERT INTO kernel.location_snapshot_version (location_id, owner_entity_id, current_version, updated_at)
    VALUES (p_location_id, p_owner_entity_id, 1, now())
    ON CONFLICT (location_id)
    DO UPDATE SET current_version = kernel.location_snapshot_version.current_version + 1,
                  updated_at = now()
    RETURNING current_version INTO new_version;

    INSERT INTO kernel.change_log (location_id, version, owner_entity_id, table_name, row_id, op, apply_from, urgent)
    SELECT DISTINCT ON (t.table_name, t.row_id)
           p_location_id, new_version, p_owner_entity_id, t.table_name, t.row_id, t.op, p_apply_from,
           coalesce(p_urgent, false)
      FROM unnest(p_table_names, p_row_ids, p_ops) AS t (table_name, row_id, op)
     ORDER BY t.table_name, t.row_id;

    RETURN new_version;
END;
$$;

REVOKE ALL ON FUNCTION kernel.change_log_append(uuid, uuid, text[], uuid[], text[], date, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.change_log_append(uuid, uuid, text[], uuid[], text[], date, boolean) TO app_rw;
