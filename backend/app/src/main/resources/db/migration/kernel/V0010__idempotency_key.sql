CREATE TABLE kernel.idempotency_key (
    user_id uuid NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    created_on date NOT NULL
        DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,
    request_hash varchar(64) NOT NULL,
    response_status integer,
    response_body text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT idempotency_key_pk
        PRIMARY KEY (
            user_id,
            idempotency_key,
            created_on
        ),

    CONSTRAINT idempotency_key_request_hash_ck
        CHECK (request_hash ~ '^[0-9a-f]{64}$')
) PARTITION BY RANGE (created_on);

ALTER TABLE kernel.idempotency_key
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE kernel.idempotency_key
    FORCE ROW LEVEL SECURITY;

CREATE POLICY idempotency_key_read
    ON kernel.idempotency_key
    FOR SELECT
    USING (
        user_id =
        NULLIF(current_setting('app.user_id', true), '')::uuid
    );

CREATE POLICY idempotency_key_insert
    ON kernel.idempotency_key
    FOR INSERT
    WITH CHECK (
        user_id =
        NULLIF(current_setting('app.user_id', true), '')::uuid
    );

CREATE POLICY idempotency_key_update
    ON kernel.idempotency_key
    FOR UPDATE
    USING (
        user_id =
        NULLIF(current_setting('app.user_id', true), '')::uuid
    )
    WITH CHECK (
        user_id =
        NULLIF(current_setting('app.user_id', true), '')::uuid
    );

GRANT SELECT, INSERT
    ON kernel.idempotency_key
    TO app_rw;

GRANT UPDATE (response_status, response_body)
    ON kernel.idempotency_key
    TO app_rw;

REVOKE DELETE, TRUNCATE
    ON kernel.idempotency_key
    FROM app_rw;

-- K03A_BOOTSTRAP_PARTITIONS
-- Bootstrap yesterday/current/future daily partitions so the application can
-- accept commands immediately after Flyway completes. Ongoing maintenance is
-- performed by IdempotencyPartitionJob with migrator rights.
DO $$
DECLARE
    partition_date date;
    partition_name text;
BEGIN
    FOR offset_days IN -1..2 LOOP
        partition_date := (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date + offset_days;
        partition_name := 'idempotency_key_' || to_char(partition_date, 'YYYYMMDD');

        EXECUTE format(
            'CREATE TABLE kernel.%I PARTITION OF kernel.idempotency_key
             FOR VALUES FROM (%L) TO (%L)',
            partition_name,
            partition_date,
            partition_date + 1
        );

        EXECUTE format(
            'ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY',
            partition_name
        );

        EXECUTE format(
            'ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY',
            partition_name
        );

        EXECUTE format(
            'DROP POLICY IF EXISTS idempotency_key_read ON kernel.%I',
            partition_name
        );

        EXECUTE format(
            'CREATE POLICY idempotency_key_read ON kernel.%I
             FOR SELECT
             USING (
                 user_id =
                 NULLIF(current_setting(''app.user_id'', true), '''')::uuid
             )',
            partition_name
        );

        EXECUTE format(
            'DROP POLICY IF EXISTS idempotency_key_insert ON kernel.%I',
            partition_name
        );

        EXECUTE format(
            'CREATE POLICY idempotency_key_insert ON kernel.%I
             FOR INSERT
             WITH CHECK (
                 user_id =
                 NULLIF(current_setting(''app.user_id'', true), '''')::uuid
             )',
            partition_name
        );

        EXECUTE format(
            'DROP POLICY IF EXISTS idempotency_key_update ON kernel.%I',
            partition_name
        );

        EXECUTE format(
            'CREATE POLICY idempotency_key_update ON kernel.%I
             FOR UPDATE
             USING (
                 user_id =
                 NULLIF(current_setting(''app.user_id'', true), '''')::uuid
             )
             WITH CHECK (
                 user_id =
                 NULLIF(current_setting(''app.user_id'', true), '''')::uuid
             )',
            partition_name
        );

        EXECUTE format(
            'REVOKE DELETE, TRUNCATE ON kernel.%I FROM app_rw',
            partition_name
        );
    END LOOP;
END
$$;
