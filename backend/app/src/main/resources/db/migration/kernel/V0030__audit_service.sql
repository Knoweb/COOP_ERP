CREATE TABLE kernel.audit_event_type (
    event_type_code varchar(40) PRIMARY KEY,
    severity text NOT NULL CHECK (severity IN ('INFO', 'REVIEW', 'ALERT')),
    reviewer_role_template uuid,
    offline_capturable boolean NOT NULL DEFAULT false,
    description_en text NOT NULL
);

INSERT INTO kernel.audit_event_type (
    event_type_code,
    severity,
    reviewer_role_template,
    offline_capturable,
    description_en
)
VALUES
    ('HELLO_GREETING_REGISTERED', 'INFO', NULL, false, 'Hello greeting registered'),
    ('ENTITY_REGISTERED', 'INFO', NULL, false, 'Entity registered'),
    ('ENTITY_ACTIVATED', 'INFO', NULL, false, 'Entity activated'),
    ('ENTITY_SUSPENDED', 'INFO', NULL, false, 'Entity suspended'),
    ('ENTITY_REINSTATED', 'INFO', NULL, false, 'Entity reinstated'),
    ('ENTITY_UPDATED', 'INFO', NULL, false, 'Responsible officer appointment')
ON CONFLICT (event_type_code) DO NOTHING;

ALTER TABLE kernel.audit_event_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.audit_event_type FORCE ROW LEVEL SECURITY;

CREATE POLICY catalogue_read
    ON kernel.audit_event_type
    FOR SELECT
    TO app_rw
    USING (true);

CREATE POLICY seed_manage
    ON kernel.audit_event_type
    FOR ALL
    TO app_seed
    USING (true)
    WITH CHECK (true);

REVOKE ALL PRIVILEGES ON kernel.audit_event_type FROM PUBLIC;
REVOKE ALL PRIVILEGES ON kernel.audit_event_type FROM app_rw;
GRANT SELECT ON kernel.audit_event_type TO app_rw;

GRANT SELECT, INSERT, UPDATE ON kernel.audit_event_type TO app_seed;
REVOKE DELETE, TRUNCATE ON kernel.audit_event_type FROM app_seed;


CREATE TABLE kernel.audit_event (
    audit_id uuid NOT NULL,
    event_type_code varchar(40) NOT NULL
        REFERENCES kernel.audit_event_type (event_type_code),
    occurred_at timestamptz NOT NULL,
    occurred_local timestamp,
    received_at timestamptz NOT NULL DEFAULT now(),
    owner_entity_id uuid NOT NULL,
    location_id uuid,
    actor_user_id uuid,
    actor_system varchar(40),
    device_id uuid,
    till_position_id uuid,
    subject_table varchar(40) NOT NULL,
    subject_id uuid NOT NULL,
    document_id uuid,
    before_state jsonb,
    after_state jsonb,
    reason_code text,
    reason_text text,
    witness_user_id uuid,
    device_seq bigint,
    correlation_id uuid NOT NULL,
    prev_hash char(64),
    row_hash char(64),
    PRIMARY KEY (received_at, audit_id)
) PARTITION BY RANGE (received_at);

CREATE INDEX ON kernel.audit_event (owner_entity_id, received_at DESC);
CREATE INDEX ON kernel.audit_event (document_id)
    WHERE document_id IS NOT NULL;
CREATE INDEX ON kernel.audit_event (event_type_code, received_at DESC);

ALTER TABLE kernel.audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.audit_event FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read
    ON kernel.audit_event
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR location_id = kernel.scope_location()
        )
    );

CREATE POLICY own_write
    ON kernel.audit_event
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY fed_view
    ON kernel.audit_event
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'FEDERATION_VIEW'
    );

CREATE POLICY ext_view
    ON kernel.audit_event
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
        AND owner_entity_id = ANY (kernel.granted_entities())
    );

REVOKE ALL PRIVILEGES ON kernel.audit_event FROM PUBLIC;
REVOKE ALL PRIVILEGES ON kernel.audit_event FROM app_rw;
GRANT INSERT ON kernel.audit_event TO app_rw;

REVOKE UPDATE, DELETE, TRUNCATE ON kernel.audit_event FROM app_rw;


CREATE OR REPLACE FUNCTION kernel.ensure_audit_partitions(
    months_ahead integer DEFAULT 3
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, kernel
AS $$
DECLARE
    offset_month integer;
    partition_from timestamptz;
    partition_to timestamptz;
    partition_name text;
    policy_exists boolean;
BEGIN
    IF months_ahead < 0 OR months_ahead > 24 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 24';
    END IF;

    FOR offset_month IN 0..months_ahead LOOP
        partition_from :=
            (
                date_trunc(
                    'month',
                    current_timestamp AT TIME ZONE 'UTC'
                )
                + make_interval(months => offset_month)
            ) AT TIME ZONE 'UTC';

        partition_to :=
            (
                date_trunc(
                    'month',
                    current_timestamp AT TIME ZONE 'UTC'
                )
                + make_interval(months => offset_month + 1)
            ) AT TIME ZONE 'UTC';

        partition_name :=
            'audit_event_'
            || to_char(
                partition_from AT TIME ZONE 'UTC',
                'YYYY_MM'
            );

        IF to_regclass('kernel.' || partition_name) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE kernel.%I PARTITION OF kernel.audit_event '
                || 'FOR VALUES FROM (%L) TO (%L)',
                partition_name,
                partition_from,
                partition_to
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

        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_policy p
            JOIN pg_catalog.pg_class c
              ON c.oid = p.polrelid
            JOIN pg_catalog.pg_namespace n
              ON n.oid = c.relnamespace
            WHERE n.nspname = 'kernel'
              AND c.relname = partition_name
              AND p.polname = 'own_read'
        )
        INTO policy_exists;

        IF NOT policy_exists THEN
            EXECUTE format(
                'CREATE POLICY own_read ON kernel.%I '
                || 'FOR SELECT TO app_rw USING ('
                || 'kernel.scope_class() = ''OWN'' '
                || 'AND owner_entity_id = kernel.scope_entity() '
                || 'AND (kernel.scope_location() IS NULL '
                || 'OR location_id = kernel.scope_location()))',
                partition_name
            );
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_policy p
            JOIN pg_catalog.pg_class c
              ON c.oid = p.polrelid
            JOIN pg_catalog.pg_namespace n
              ON n.oid = c.relnamespace
            WHERE n.nspname = 'kernel'
              AND c.relname = partition_name
              AND p.polname = 'own_write'
        )
        INTO policy_exists;

        IF NOT policy_exists THEN
            EXECUTE format(
                'CREATE POLICY own_write ON kernel.%I '
                || 'FOR INSERT TO app_rw WITH CHECK ('
                || 'kernel.scope_class() = ''OWN'' '
                || 'AND owner_entity_id = kernel.scope_entity())',
                partition_name
            );
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_policy p
            JOIN pg_catalog.pg_class c
              ON c.oid = p.polrelid
            JOIN pg_catalog.pg_namespace n
              ON n.oid = c.relnamespace
            WHERE n.nspname = 'kernel'
              AND c.relname = partition_name
              AND p.polname = 'fed_view'
        )
        INTO policy_exists;

        IF NOT policy_exists THEN
            EXECUTE format(
                'CREATE POLICY fed_view ON kernel.%I '
                || 'FOR SELECT TO app_rw USING ('
                || 'kernel.scope_class() = ''FEDERATION_VIEW'')',
                partition_name
            );
        END IF;

        SELECT EXISTS (
            SELECT 1
            FROM pg_catalog.pg_policy p
            JOIN pg_catalog.pg_class c
              ON c.oid = p.polrelid
            JOIN pg_catalog.pg_namespace n
              ON n.oid = c.relnamespace
            WHERE n.nspname = 'kernel'
              AND c.relname = partition_name
              AND p.polname = 'ext_view'
        )
        INTO policy_exists;

        IF NOT policy_exists THEN
            EXECUTE format(
                'CREATE POLICY ext_view ON kernel.%I '
                || 'FOR SELECT TO app_rw USING ('
                || 'kernel.scope_class() = ''EXTERNAL_TIMEBOXED'' '
                || 'AND owner_entity_id = ANY '
                || '(kernel.granted_entities()))',
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
            'REVOKE UPDATE, DELETE, TRUNCATE '
            || 'ON kernel.%I FROM app_rw',
            partition_name
        );
    END LOOP;
END;
$$;

REVOKE ALL
    ON FUNCTION kernel.ensure_audit_partitions(integer)
    FROM PUBLIC;

GRANT EXECUTE
    ON FUNCTION kernel.ensure_audit_partitions(integer)
    TO app_rw;

SELECT kernel.ensure_audit_partitions(3);