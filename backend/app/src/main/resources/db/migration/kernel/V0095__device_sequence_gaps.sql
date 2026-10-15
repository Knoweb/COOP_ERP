-- V0095: Device source sequence gap detection function (K-05, CR-19A-3)
--
-- Checks density of (source, source_seq) for device sources in kernel.event_outbox.
-- Central events (source = 'central') are strictly excluded (CR-19A-3).
-- Sequences explained by sync_quarantine (not REPAIRED) or sync_sequence_gap (audited resets)
-- are excluded from gap reporting (doc 18 E-I2, doc 32 sections 7-8).
--
-- SECURITY DEFINER: app_rw is INSERT-only on kernel.event_outbox. Like V0093's audit review
-- reader, a dedicated NOLOGIN role (kernel_gap_reader) owns this function and holds SELECT
-- under tenant-isolating RLS policies, providing the gap summary without exposing raw ledger rows,
-- payloads, or direct SELECT privileges to app_rw.

-- ---- the role ---------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kernel_gap_reader') THEN
        PERFORM set_config('createrole_self_grant', 'set', true);
        CREATE ROLE kernel_gap_reader NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA kernel TO kernel_gap_reader;
GRANT EXECUTE ON FUNCTION kernel.scope_class(), kernel.scope_entity(), kernel.scope_location()
    TO kernel_gap_reader;

-- Tables read by the gap check under tenant-isolating RLS policies
GRANT SELECT ON kernel.device_sync_cursor TO kernel_gap_reader;
DROP POLICY IF EXISTS gap_reader_cursor_read ON kernel.device_sync_cursor;
CREATE POLICY gap_reader_cursor_read ON kernel.device_sync_cursor FOR SELECT TO kernel_gap_reader
    USING (kernel.scope_class() = 'FEDERATION_VIEW'
           OR (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity()));

GRANT SELECT ON kernel.sync_quarantine TO kernel_gap_reader;
DROP POLICY IF EXISTS gap_reader_quarantine_read ON kernel.sync_quarantine;
CREATE POLICY gap_reader_quarantine_read ON kernel.sync_quarantine FOR SELECT TO kernel_gap_reader
    USING (kernel.scope_class() = 'FEDERATION_VIEW'
           OR (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity()));

GRANT SELECT ON kernel.sync_sequence_gap TO kernel_gap_reader;
DROP POLICY IF EXISTS gap_reader_seq_gap_read ON kernel.sync_sequence_gap;
CREATE POLICY gap_reader_seq_gap_read ON kernel.sync_sequence_gap FOR SELECT TO kernel_gap_reader
    USING (kernel.scope_class() = 'FEDERATION_VIEW'
           OR (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity()));

GRANT SELECT ON kernel.event_outbox TO kernel_gap_reader;
DROP POLICY IF EXISTS gap_reader_outbox_read ON kernel.event_outbox;
CREATE POLICY gap_reader_outbox_read ON kernel.event_outbox FOR SELECT TO kernel_gap_reader
    USING (kernel.scope_class() = 'FEDERATION_VIEW'
           OR (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity()));

-- Grants and policies across existing partitions of event_outbox
DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT c.relname FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'kernel' AND c.relname LIKE 'event_outbox_20%' AND c.relkind IN ('r', 'p')
    LOOP
        EXECUTE format('GRANT SELECT ON kernel.%I TO kernel_gap_reader', rec.relname);
        EXECUTE format('DROP POLICY IF EXISTS gap_reader_outbox_read ON kernel.%I', rec.relname);
        EXECUTE format('CREATE POLICY gap_reader_outbox_read ON kernel.%I FOR SELECT TO kernel_gap_reader '
                       || 'USING (kernel.scope_class() = ''FEDERATION_VIEW'' '
                       || 'OR (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity()))', rec.relname);
    END LOOP;
END
$$;

-- Future partitions created by ensure_event_outbox_partitions must grant to kernel_gap_reader
CREATE OR REPLACE FUNCTION kernel.ensure_event_outbox_partitions(
    months_ahead integer
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, kernel, pg_temp
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

        IF NOT EXISTS (SELECT 1 FROM pg_policies
                        WHERE schemaname = 'kernel' AND tablename = partition_name
                          AND policyname = 'gap_reader_outbox_read') THEN
            EXECUTE format('CREATE POLICY gap_reader_outbox_read ON kernel.%I FOR SELECT TO kernel_gap_reader '
                           || 'USING (kernel.scope_class() = ''FEDERATION_VIEW'' '
                           || 'OR (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity()))',
                           partition_name);
        END IF;

        EXECUTE format('REVOKE ALL PRIVILEGES ON kernel.%I FROM PUBLIC', partition_name);
        EXECUTE format('REVOKE ALL PRIVILEGES ON kernel.%I FROM app_rw', partition_name);
        EXECUTE format('GRANT INSERT ON kernel.%I TO app_rw', partition_name);
        EXECUTE format('GRANT SELECT, UPDATE (published_at) ON kernel.%I TO app_relay', partition_name);
        EXECUTE format('GRANT SELECT ON kernel.%I TO kernel_gap_reader', partition_name);
        EXECUTE format('REVOKE DELETE, TRUNCATE ON kernel.%I FROM app_rw, app_relay, kernel_gap_reader', partition_name);
    END LOOP;
END;
$$;

-- ---- the function -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION kernel.device_sequence_gaps()
RETURNS TABLE (
    device_id uuid,
    source text,
    owner_entity_id uuid,
    expected_count bigint,
    found_count bigint,
    first_missing bigint
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, kernel, pg_temp
AS $$
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'OWN' AND kernel.scope_class() IS DISTINCT FROM 'FEDERATION_VIEW' THEN
        RAISE EXCEPTION 'device_sequence_gaps requires OWN or FEDERATION_VIEW scope'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    RETURN QUERY
    WITH cursor_devices AS (
        SELECT c.device_id,
               c.device_id::text AS src,
               c.owner_entity_id,
               c.last_applied_seq AS cursor_seq
          FROM kernel.device_sync_cursor c
         WHERE (kernel.scope_class() = 'FEDERATION_VIEW' OR c.owner_entity_id = kernel.scope_entity())
    ),
    valid_outbox_events AS (
        SELECT (CASE WHEN pg_input_is_valid(e.source, 'uuid') THEN e.source::uuid ELSE NULL END) AS device_id,
               (CASE WHEN pg_input_is_valid(e.source, 'uuid') THEN e.source::uuid::text ELSE NULL END) AS src,
               e.owner_entity_id
          FROM kernel.event_outbox e
         WHERE e.source <> 'central'
           AND pg_input_is_valid(e.source, 'uuid')
           AND (kernel.scope_class() = 'FEDERATION_VIEW' OR e.owner_entity_id = kernel.scope_entity())
    ),
    outbox_devices AS (
        SELECT v.device_id,
               v.src,
               MAX(v.owner_entity_id::text)::uuid AS owner_entity_id,
               0::bigint AS cursor_seq
          FROM valid_outbox_events v
         WHERE v.device_id IS NOT NULL
           AND NOT EXISTS (
               SELECT 1 FROM kernel.device_sync_cursor c2 WHERE c2.device_id = v.device_id
           )
         GROUP BY v.device_id, v.src
    ),
    candidate_devices AS (
        SELECT cd.device_id, cd.src, cd.owner_entity_id, cd.cursor_seq FROM cursor_devices cd
        UNION ALL
        SELECT od.device_id, od.src, od.owner_entity_id, od.cursor_seq FROM outbox_devices od
    ),
    device_bounds AS (
        SELECT cd.device_id,
               cd.src,
               cd.owner_entity_id,
               GREATEST(
                   cd.cursor_seq,
                   COALESCE((
                       SELECT MAX(e.source_seq)
                         FROM kernel.event_outbox e
                        WHERE (e.source = cd.src OR (pg_input_is_valid(e.source, 'uuid') AND e.source::uuid = cd.device_id))
                   ), 0)
               ) AS max_seq
          FROM candidate_devices cd
    ),
    active_devices AS (
        SELECT db.device_id,
               db.src,
               db.owner_entity_id,
               db.max_seq AS expected_count
          FROM device_bounds db
         WHERE db.max_seq > 0
    ),
    first_missing_eval AS (
        SELECT ad.device_id,
               ad.src,
               ad.owner_entity_id,
               ad.expected_count,
               (
                   SELECT COALESCE(
                       -- Case 1: Sequence 1 is missing
                       (SELECT 1::bigint
                         WHERE NOT EXISTS (
                             SELECT 1 FROM kernel.event_outbox e
                              WHERE (e.source = ad.src OR (pg_input_is_valid(e.source, 'uuid') AND e.source::uuid = ad.device_id))
                                AND e.source_seq = 1
                         )
                         AND NOT EXISTS (
                             SELECT 1 FROM kernel.sync_quarantine q
                              WHERE q.device_id = ad.device_id AND q.device_seq = 1
                                AND q.resolution IS DISTINCT FROM 'REPAIRED'
                         )
                         AND NOT EXISTS (
                             SELECT 1 FROM kernel.sync_sequence_gap g
                              WHERE g.device_id = ad.device_id AND 1 BETWEEN g.from_seq AND g.to_seq
                         )
                       ),
                       -- Case 2: Candidate next numbers (k + 1)
                       (SELECT MIN(cand.next_seq)
                          FROM (
                              SELECT e.source_seq + 1 AS next_seq
                                FROM kernel.event_outbox e
                               WHERE (e.source = ad.src OR (pg_input_is_valid(e.source, 'uuid') AND e.source::uuid = ad.device_id))
                                 AND e.source_seq < ad.expected_count
                              UNION
                              SELECT q.device_seq + 1 AS next_seq
                                FROM kernel.sync_quarantine q
                               WHERE q.device_id = ad.device_id
                                 AND q.device_seq < ad.expected_count
                                 AND q.resolution IS DISTINCT FROM 'REPAIRED'
                              UNION
                              SELECT g.to_seq + 1 AS next_seq
                                FROM kernel.sync_sequence_gap g
                               WHERE g.device_id = ad.device_id
                                 AND g.to_seq < ad.expected_count
                          ) cand
                         WHERE cand.next_seq <= ad.expected_count
                           AND NOT EXISTS (
                               SELECT 1 FROM kernel.event_outbox e
                                WHERE (e.source = ad.src OR (pg_input_is_valid(e.source, 'uuid') AND e.source::uuid = ad.device_id))
                                  AND e.source_seq = cand.next_seq
                           )
                           AND NOT EXISTS (
                               SELECT 1 FROM kernel.sync_quarantine q
                                WHERE q.device_id = ad.device_id AND q.device_seq = cand.next_seq
                                  AND q.resolution IS DISTINCT FROM 'REPAIRED'
                           )
                           AND NOT EXISTS (
                               SELECT 1 FROM kernel.sync_sequence_gap g
                                WHERE g.device_id = ad.device_id AND cand.next_seq BETWEEN g.from_seq AND g.to_seq
                           )
                       )
                   )
               ) AS first_missing
          FROM active_devices ad
    )
    SELECT fme.device_id,
           fme.src AS source,
           fme.owner_entity_id,
           fme.expected_count,
           -- found_count computed accurately via merged int8multirange without unbounded integer enumeration
           (
               SELECT COALESCE(SUM(upper(r) - lower(r)), 0)::bigint
                 FROM unnest((
                     SELECT range_agg(rng)
                       FROM (
                           SELECT int8range(e.source_seq, e.source_seq, '[]') AS rng
                             FROM kernel.event_outbox e
                            WHERE (e.source = fme.src OR (pg_input_is_valid(e.source, 'uuid') AND e.source::uuid = fme.device_id))
                              AND e.source_seq BETWEEN 1 AND fme.expected_count
                           UNION ALL
                           SELECT int8range(q.device_seq, q.device_seq, '[]') AS rng
                             FROM kernel.sync_quarantine q
                            WHERE q.device_id = fme.device_id
                              AND q.resolution IS DISTINCT FROM 'REPAIRED'
                              AND q.device_seq BETWEEN 1 AND fme.expected_count
                           UNION ALL
                           SELECT int8range(GREATEST(1, g.from_seq), LEAST(fme.expected_count, g.to_seq), '[]') AS rng
                             FROM kernel.sync_sequence_gap g
                            WHERE g.device_id = fme.device_id
                              AND g.from_seq <= fme.expected_count
                              AND g.to_seq >= 1
                       ) all_ranges
                 )) AS r
           ) AS found_count,
           fme.first_missing
      FROM first_missing_eval fme
     WHERE fme.first_missing IS NOT NULL;
END;
$$;

REVOKE ALL ON FUNCTION kernel.device_sequence_gaps() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.device_sequence_gaps() TO app_rw;

-- Assign function owner to kernel_gap_reader
GRANT CREATE ON SCHEMA kernel TO kernel_gap_reader;
ALTER FUNCTION kernel.device_sequence_gaps() OWNER TO kernel_gap_reader;
REVOKE CREATE ON SCHEMA kernel FROM kernel_gap_reader;
