GRANT USAGE ON SCHEMA kernel TO app_relay;

CREATE SEQUENCE kernel.central_source_seq;

REVOKE ALL PRIVILEGES
    ON SEQUENCE kernel.central_source_seq
    FROM PUBLIC;

GRANT USAGE
    ON SEQUENCE kernel.central_source_seq
    TO app_rw;


CREATE TABLE kernel.event_outbox (
    event_id uuid NOT NULL,
    event_type text NOT NULL,
    occurred_at timestamptz NOT NULL,
    occurred_local timestamp,
    received_at timestamptz NOT NULL DEFAULT now(),

    source text NOT NULL,
    source_seq bigint NOT NULL,

    owner_entity_id uuid NOT NULL,
    location_id uuid,

    aggregate_type text NOT NULL,
    aggregate_id uuid NOT NULL,

    correlation_id uuid NOT NULL,
    causation_id uuid,
    actor_user_id uuid,
    engine_version text,

    payload jsonb NOT NULL,
    published_at timestamptz,

    PRIMARY KEY (received_at, event_id),
    UNIQUE (source, source_seq, received_at)
) PARTITION BY RANGE (received_at);

CREATE INDEX event_outbox_relay_scan_idx
    ON kernel.event_outbox (source, source_seq)
    WHERE published_at IS NULL;

CREATE INDEX event_outbox_owner_received_idx
    ON kernel.event_outbox (owner_entity_id, received_at DESC);

ALTER TABLE kernel.event_outbox
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE kernel.event_outbox
    FORCE ROW LEVEL SECURITY;

CREATE POLICY event_outbox_app_insert
    ON kernel.event_outbox
    FOR INSERT
    TO app_rw
    WITH CHECK (
        owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY event_outbox_relay_select
    ON kernel.event_outbox
    FOR SELECT
    TO app_relay
    USING (true);

CREATE POLICY event_outbox_relay_update
    ON kernel.event_outbox
    FOR UPDATE
    TO app_relay
    USING (true)
    WITH CHECK (true);

REVOKE ALL PRIVILEGES
    ON kernel.event_outbox
    FROM PUBLIC;

REVOKE ALL PRIVILEGES
    ON kernel.event_outbox
    FROM app_rw;

GRANT INSERT
    ON kernel.event_outbox
    TO app_rw;

GRANT SELECT,
      UPDATE (published_at)
    ON kernel.event_outbox
    TO app_relay;

REVOKE DELETE, TRUNCATE
    ON kernel.event_outbox
    FROM app_rw, app_relay;


CREATE TABLE kernel.event_inbox (
    consumer text NOT NULL,
    event_id uuid NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT now(),
    outcome text NOT NULL
        CHECK (outcome IN ('APPLIED', 'SKIPPED', 'FAILED')),
    last_error text,
    PRIMARY KEY (consumer, event_id)
);

ALTER TABLE kernel.event_inbox
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE kernel.event_inbox
    FORCE ROW LEVEL SECURITY;

CREATE POLICY event_inbox_consumer
    ON kernel.event_inbox
    FOR ALL
    TO app_rw
    USING (true)
    WITH CHECK (true);

REVOKE ALL PRIVILEGES
    ON kernel.event_inbox
    FROM PUBLIC;

REVOKE ALL PRIVILEGES
    ON kernel.event_inbox
    FROM app_rw;

GRANT SELECT, INSERT
    ON kernel.event_inbox
    TO app_rw;

GRANT UPDATE (applied_at, outcome, last_error)
    ON kernel.event_inbox
    TO app_rw;

REVOKE DELETE, TRUNCATE
    ON kernel.event_inbox
    FROM app_rw;


CREATE OR REPLACE FUNCTION kernel.ensure_event_outbox_partitions(
    months_ahead integer
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, kernel
AS $$
DECLARE
    month_offset integer;
    partition_from date;
    partition_until date;
    partition_name text;
BEGIN
    IF months_ahead < 0 THEN
        RAISE EXCEPTION 'months_ahead must not be negative';
    END IF;

    FOR month_offset IN 0..months_ahead LOOP

        partition_from :=
            (
                date_trunc(
                    'month',
                    CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
                )
                + make_interval(months => month_offset)
            )::date;

        partition_until :=
            (
                date_trunc(
                    'month',
                    CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
                )
                + make_interval(months => month_offset + 1)
            )::date;

        partition_name :=
            'event_outbox_' || to_char(partition_from, 'YYYYMM');

        IF to_regclass('kernel.' || partition_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE kernel.%I '
                || 'PARTITION OF kernel.event_outbox '
                || 'FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                partition_from,
                partition_until
            );
        END IF;

        EXECUTE format(
            'ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY',
            partition_name
        );

        EXECUTE format(
            'ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY',
            partition_name
        );

        IF NOT EXISTS (
            SELECT 1
            FROM pg_policies
            WHERE schemaname = 'kernel'
              AND tablename = partition_name
              AND policyname = 'event_outbox_app_insert'
        ) THEN
            EXECUTE format(
                'CREATE POLICY event_outbox_app_insert '
                || 'ON kernel.%I '
                || 'FOR INSERT TO app_rw '
                || 'WITH CHECK ('
                || 'owner_entity_id = kernel.scope_entity()'
                || ')',
                partition_name
            );
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM pg_policies
            WHERE schemaname = 'kernel'
              AND tablename = partition_name
              AND policyname = 'event_outbox_relay_select'
        ) THEN
            EXECUTE format(
                'CREATE POLICY event_outbox_relay_select '
                || 'ON kernel.%I '
                || 'FOR SELECT TO app_relay '
                || 'USING (true)',
                partition_name
            );
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM pg_policies
            WHERE schemaname = 'kernel'
              AND tablename = partition_name
              AND policyname = 'event_outbox_relay_update'
        ) THEN
            EXECUTE format(
                'CREATE POLICY event_outbox_relay_update '
                || 'ON kernel.%I '
                || 'FOR UPDATE TO app_relay '
                || 'USING (true) WITH CHECK (true)',
                partition_name
            );
        END IF;

        EXECUTE format(
            'REVOKE ALL PRIVILEGES ON kernel.%I FROM PUBLIC',
            partition_name
        );

        EXECUTE format(
            'REVOKE ALL PRIVILEGES ON kernel.%I FROM app_rw',
            partition_name
        );

        EXECUTE format(
            'GRANT INSERT ON kernel.%I TO app_rw',
            partition_name
        );

        EXECUTE format(
            'GRANT SELECT, UPDATE (published_at) '
            || 'ON kernel.%I TO app_relay',
            partition_name
        );

        EXECUTE format(
            'REVOKE DELETE, TRUNCATE '
            || 'ON kernel.%I FROM app_rw, app_relay',
            partition_name
        );

    END LOOP;
END;
$$;

REVOKE ALL PRIVILEGES
    ON FUNCTION kernel.ensure_event_outbox_partitions(integer)
    FROM PUBLIC;

GRANT EXECUTE
    ON FUNCTION kernel.ensure_event_outbox_partitions(integer)
    TO app_rw;

SELECT kernel.ensure_event_outbox_partitions(3);

-- K-05 relay read access
GRANT SELECT ON TABLE kernel.event_outbox TO app_relay;
