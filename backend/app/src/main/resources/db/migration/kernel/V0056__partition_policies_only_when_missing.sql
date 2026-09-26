-- The two partition functions of V0053 dropped and recreated own_read, own_write and
-- event_outbox_app_insert on every partition on every run, which takes an ACCESS EXCLUSIVE
-- lock on the live partitions every command writes to: at 00:10 partition-maintenance waited
-- behind any long transaction on the month's partitions and every audit or outbox insert
-- queued behind it (review of 26 Sep). A merged migration is never edited: the functions are
-- replaced here so that a policy is recreated only when it is missing or when its text lacks
-- the class test of CR-17A-3 (a partition made by the text of V0030 or V0031). The policy
-- text itself is that of V0053, unchanged; OwnPoliciesTestTheClassIntegrationTest keeps it so.

-- ---- the audit log ---------------------------------------------------------------------------

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
BEGIN
    IF months_ahead < 0 OR months_ahead > 24 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 24';
    END IF;

    FOR offset_month IN 0..months_ahead LOOP
        partition_from := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                           + make_interval(months => offset_month)) AT TIME ZONE 'UTC';
        partition_to := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                         + make_interval(months => offset_month + 1)) AT TIME ZONE 'UTC';
        partition_name := 'audit_event_' || to_char(partition_from AT TIME ZONE 'UTC', 'YYYY_MM');

        IF to_regclass('kernel.' || partition_name) IS NULL THEN
            EXECUTE format('CREATE TABLE kernel.%I PARTITION OF kernel.audit_event FOR VALUES FROM (%L) TO (%L)',
                           partition_name, partition_from, partition_to);
        END IF;

        -- ALTER TABLE takes the same lock as a policy change: only when it changes something.
        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'kernel' AND c.relname = partition_name
                          AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            EXECUTE format('ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY', partition_name);
            EXECUTE format('ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY', partition_name);
        END IF;

        -- own_read: recreated only when missing or ungated (the class test is in its USING).
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name AND policyname = 'own_read'
                          AND coalesce(qual, '') LIKE '%scope_class()%') THEN
            EXECUTE format('DROP POLICY IF EXISTS own_read ON kernel.%I', partition_name);
            EXECUTE format('CREATE POLICY own_read ON kernel.%I FOR SELECT TO app_rw USING ('
                           || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity() '
                           || 'AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))',
                           partition_name);
        END IF;

        -- own_write: the same, on its WITH CHECK.
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name AND policyname = 'own_write'
                          AND coalesce(with_check, '') LIKE '%scope_class()%') THEN
            EXECUTE format('DROP POLICY IF EXISTS own_write ON kernel.%I', partition_name);
            EXECUTE format('CREATE POLICY own_write ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                           || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())',
                           partition_name);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name AND policyname = 'fed_view') THEN
            EXECUTE format('CREATE POLICY fed_view ON kernel.%I FOR SELECT TO app_rw USING ('
                           || 'kernel.scope_class() = ''FEDERATION_VIEW'')', partition_name);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name AND policyname = 'ext_view') THEN
            EXECUTE format('CREATE POLICY ext_view ON kernel.%I FOR SELECT TO app_rw USING ('
                           || 'kernel.scope_class() = ''EXTERNAL_TIMEBOXED'' '
                           || 'AND owner_entity_id = ANY (kernel.granted_entities()))', partition_name);
        END IF;

        EXECUTE format('REVOKE ALL PRIVILEGES ON kernel.%I FROM PUBLIC', partition_name);
        EXECUTE format('REVOKE ALL PRIVILEGES ON kernel.%I FROM app_rw', partition_name);
        EXECUTE format('GRANT INSERT ON kernel.%I TO app_rw', partition_name);
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON kernel.%I FROM app_rw', partition_name);
    END LOOP;
END;
$$;

-- ---- the outbox ------------------------------------------------------------------------------

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
        partition_from := (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                           + make_interval(months => month_offset))::date;
        partition_until := (date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')
                            + make_interval(months => month_offset + 1))::date;
        partition_name := 'event_outbox_' || to_char(partition_from, 'YYYYMM');

        IF to_regclass('kernel.' || partition_name) IS NULL THEN
            EXECUTE format('CREATE TABLE kernel.%I PARTITION OF kernel.event_outbox FOR VALUES FROM (%L) TO (%L)',
                           partition_name, partition_from, partition_until);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'kernel' AND c.relname = partition_name
                          AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            EXECUTE format('ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY', partition_name);
            EXECUTE format('ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY', partition_name);
        END IF;

        -- The insert policy: recreated only when missing or ungated.
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name
                          AND policyname = 'event_outbox_app_insert'
                          AND coalesce(with_check, '') LIKE '%scope_class()%') THEN
            EXECUTE format('DROP POLICY IF EXISTS event_outbox_app_insert ON kernel.%I', partition_name);
            EXECUTE format('CREATE POLICY event_outbox_app_insert ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                           || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())',
                           partition_name);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name
                          AND policyname = 'event_outbox_relay_select') THEN
            EXECUTE format('CREATE POLICY event_outbox_relay_select ON kernel.%I FOR SELECT TO app_relay USING (true)',
                           partition_name);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name
                          AND policyname = 'event_outbox_relay_update') THEN
            EXECUTE format('CREATE POLICY event_outbox_relay_update ON kernel.%I FOR UPDATE TO app_relay '
                           || 'USING (true) WITH CHECK (true)', partition_name);
        END IF;

        EXECUTE format('REVOKE ALL PRIVILEGES ON kernel.%I FROM PUBLIC', partition_name);
        EXECUTE format('REVOKE ALL PRIVILEGES ON kernel.%I FROM app_rw', partition_name);
        EXECUTE format('GRANT INSERT ON kernel.%I TO app_rw', partition_name);
        EXECUTE format('GRANT SELECT, UPDATE (published_at) ON kernel.%I TO app_relay', partition_name);
        EXECUTE format('REVOKE DELETE, TRUNCATE ON kernel.%I FROM app_rw, app_relay', partition_name);
    END LOOP;
END;
$$;
