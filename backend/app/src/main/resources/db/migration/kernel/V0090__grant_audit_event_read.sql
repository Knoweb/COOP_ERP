GRANT SELECT ON kernel.audit_event TO app_rw;

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
                || 'owner_entity_id = kernel.scope_entity() '
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
                || 'owner_entity_id = kernel.scope_entity())',
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
            'GRANT SELECT, INSERT ON kernel.%I TO app_rw',
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

-- Apply to existing partitions
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT relname FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'kernel' AND c.relname LIKE 'audit_event_20%' AND c.relkind IN ('r', 'p')
    LOOP
        EXECUTE format('GRANT SELECT ON kernel.%I TO app_rw', rec.relname);
    END LOOP;
END;
$$;
