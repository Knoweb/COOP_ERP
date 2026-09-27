-- A shop writes only at its own location (PLAN_TO_M2 task 6.12; decided 27 September 2026 on the
-- architect's delegation, docs/PROGRESS.md Deviations).
--
-- The policy template's own_write had no location line, and own_update's WITH CHECK had none
-- either: a session scoped to shop A could insert a row at sibling shop B of the same entity, or
-- move one of its rows there, and then not read it back. Doc 18 section 3.7 gives OWN "rows where
-- owner = scope entity (and location when the assignment is location-scoped)" and M-05 says a
-- shop sees nothing of a sibling shop; a row a shop could write at a sibling but not read is the
-- same leak the other way (a forged audit record, event or quarantined fact "from" the sibling).
-- kernel.document has had the line since V0061; the template (RLS_POLICY_TEMPLATE.md) now has it
-- too, and every kernel table with a location_id column gets it here. An entity-wide session
-- (no scope location) still writes anywhere in its entity.
--
-- What a shop session writes at its location, and why each still works:
--   * kernel.audit_event and kernel.event_outbox take the location from the session's own scope
--     (AuditFacadeImpl, OutboxWriter) or, for a till's upload, from the device's shop, which is
--     the scope DeviceAuth sets (DeviceEventWriter); so does kernel.sync_quarantine (EventApplier).
--   * kernel.location_business_date: a shop closes its own day; the cut-off job acts entity-wide.
--   * kernel.numbering_series: a shop creates and advances its LOCATION and TILL_POSITION series
--     and the ENTITY series (location_id NULL), which a document at a shop may draw its number
--     from (24B; V0055). own_write admits the NULL location as own_read and own_update already do.
-- Nothing in the guides writes at another location from a shop session: a transfer is issued
-- entity-wide (25A section 5, inv.transfer.issue ENTITY) and received at the destination as a new
-- receipt document there (shop.transfer.receive LOCATION); a GRN is confirmed at the receiving
-- location. Recorded for M4 and M5 in docs/PROGRESS.md.

-- ---- the audit log ---------------------------------------------------------------------------

ALTER POLICY own_write ON kernel.audit_event
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

-- ---- the outbox ------------------------------------------------------------------------------

ALTER POLICY event_outbox_app_insert ON kernel.event_outbox
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

-- ---- the partitions of both ------------------------------------------------------------------
-- An insert through the parent is checked against the parent's policies only; a partition's own
-- policies apply to a statement that names the partition. They carry the same text, so that no
-- door is left open (OwnPoliciesTestTheClassIntegrationTest reads every one).

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
        EXECUTE format('DROP POLICY IF EXISTS own_write ON kernel.%I', child);
        EXECUTE format('CREATE POLICY own_write ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                       || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity() '
                       || 'AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))', child);
    END LOOP;

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
                       || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity() '
                       || 'AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))', child);
    END LOOP;
END
$$;

-- The partition functions of V0056, with the location line. As there, a policy is recreated
-- only when it is missing or its text lacks what this version requires (here the location
-- line), so the nightly maintenance takes no lock on a partition that is already right.

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

        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'kernel' AND c.relname = partition_name
                          AND c.relrowsecurity AND c.relforcerowsecurity) THEN
            EXECUTE format('ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY', partition_name);
            EXECUTE format('ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY', partition_name);
        END IF;

        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name AND policyname = 'own_read'
                          AND coalesce(qual, '') LIKE '%scope_class()%') THEN
            EXECUTE format('DROP POLICY IF EXISTS own_read ON kernel.%I', partition_name);
            EXECUTE format('CREATE POLICY own_read ON kernel.%I FOR SELECT TO app_rw USING ('
                           || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity() '
                           || 'AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))',
                           partition_name);
        END IF;

        -- own_write: recreated when missing, ungated, or without the location line.
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name AND policyname = 'own_write'
                          AND coalesce(with_check, '') LIKE '%scope_class()%'
                          AND coalesce(with_check, '') LIKE '%scope_location()%') THEN
            EXECUTE format('DROP POLICY IF EXISTS own_write ON kernel.%I', partition_name);
            EXECUTE format('CREATE POLICY own_write ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                           || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity() '
                           || 'AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))',
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

        -- The insert policy: recreated when missing, ungated, or without the location line.
        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name
                          AND policyname = 'event_outbox_app_insert'
                          AND coalesce(with_check, '') LIKE '%scope_class()%'
                          AND coalesce(with_check, '') LIKE '%scope_location()%') THEN
            EXECUTE format('DROP POLICY IF EXISTS event_outbox_app_insert ON kernel.%I', partition_name);
            EXECUTE format('CREATE POLICY event_outbox_app_insert ON kernel.%I FOR INSERT TO app_rw WITH CHECK ('
                           || 'kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity() '
                           || 'AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))',
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

-- ---- the day-close state ---------------------------------------------------------------------

ALTER POLICY own_write ON kernel.location_business_date
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

ALTER POLICY own_update ON kernel.location_business_date
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

-- ---- the numbering series: the ENTITY series (location NULL) stays the whole entity's --------

ALTER POLICY own_write ON kernel.numbering_series
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL
                     OR location_id IS NULL
                     OR location_id = kernel.scope_location()));

ALTER POLICY own_update ON kernel.numbering_series
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL
                OR location_id IS NULL
                OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL
                     OR location_id IS NULL
                     OR location_id = kernel.scope_location()));

-- kernel.sync_quarantine is created by V0080, after this migration on a fresh database: its
-- own_write gains the line in V0083.
