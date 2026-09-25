-- CR-17A-3 for the kernel's own ledgers: own_read and own_write on the audit log and the
-- outbox's insert policy test the scope class as well as the entity. Without the class test
-- a FEDERATION_VIEW or EXTERNAL_TIMEBOXED caller, whose scope entity is set too, read and
-- wrote the rows of that entity through own_*, and doc 18 section 3.7 makes those classes
-- read-only. The template is db/migration/RLS_POLICY_TEMPLATE.md; RlsMatrixIntegrationTest
-- proves it; OwnPoliciesTestTheClassIntegrationTest keeps every own_* policy in every schema to it.
-- A merged migration is never edited: the policies are altered here, on the parents and on
-- every partition that exists, and the two partition functions are replaced so that the
-- partitions they create from now on carry the gated text.

-- ---- the audit log ---------------------------------------------------------------------------

ALTER POLICY own_read ON kernel.audit_event
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

ALTER POLICY own_write ON kernel.audit_event
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

DO $$
DECLARE
    child text;
BEGIN
    FOR child IN
        SELECT c.relname
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
          JOIN pg_namespace n ON n.oid = p.relnamespace
         WHERE n.nspname = 'kernel' AND p.relname = 'audit_event'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS own_read ON kernel.%I', child);
        EXECUTE format('CREATE POLICY own_read ON kernel.%I FOR SELECT TO app_rw USING ('
                       || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity() '
                       || 'AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))', child);
        EXECUTE format('DROP POLICY IF EXISTS own_write ON kernel.%I', child);
        EXECUTE format('CREATE POLICY own_write ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                       || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())', child);
    END LOOP;
END
$$;

-- The partition function of V0030, with the gated own_* text. Every run drops and recreates
-- own_read and own_write on each partition it touches, so a partition made by the old text
-- is corrected the next night without a further migration.
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

        EXECUTE format('ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY', partition_name);
        EXECUTE format('ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY', partition_name);

        EXECUTE format('DROP POLICY IF EXISTS own_read ON kernel.%I', partition_name);
        EXECUTE format('CREATE POLICY own_read ON kernel.%I FOR SELECT TO app_rw USING ('
                       || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity() '
                       || 'AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))',
                       partition_name);
        EXECUTE format('DROP POLICY IF EXISTS own_write ON kernel.%I', partition_name);
        EXECUTE format('CREATE POLICY own_write ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                       || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())',
                       partition_name);

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

ALTER POLICY event_outbox_app_insert ON kernel.event_outbox
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

DO $$
DECLARE
    child text;
BEGIN
    FOR child IN
        SELECT c.relname
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
          JOIN pg_namespace n ON n.oid = p.relnamespace
         WHERE n.nspname = 'kernel' AND p.relname = 'event_outbox'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS event_outbox_app_insert ON kernel.%I', child);
        EXECUTE format('CREATE POLICY event_outbox_app_insert ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                       || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())', child);
    END LOOP;
END
$$;

-- The partition function of V0031, with the gated insert policy, recreated on every run.
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

        EXECUTE format('ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY', partition_name);
        EXECUTE format('ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY', partition_name);

        EXECUTE format('DROP POLICY IF EXISTS event_outbox_app_insert ON kernel.%I', partition_name);
        EXECUTE format('CREATE POLICY event_outbox_app_insert ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                       || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())',
                       partition_name);

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
