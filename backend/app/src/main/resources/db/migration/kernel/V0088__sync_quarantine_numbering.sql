-- FIX-06: skip numbering gaps genuinely explained by sync quarantine.
--
-- Adds series_id and doc_number to kernel.sync_quarantine to preserve the association
-- permanently, surviving the nullification of raw_event.
-- kernel.numbering_gaps() skips a gap if a quarantine record exists for the same
-- series and number, provided the record belongs to the series holder and is not REPAIRED.

ALTER TABLE kernel.sync_quarantine
    ADD COLUMN series_id uuid,
    ADD COLUMN doc_number bigint;

CREATE OR REPLACE FUNCTION kernel.sync_quarantine_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.quarantine_id IS DISTINCT FROM OLD.quarantine_id
       OR NEW.device_id IS DISTINCT FROM OLD.device_id
       OR NEW.owner_entity_id IS DISTINCT FROM OLD.owner_entity_id
       OR NEW.location_id IS DISTINCT FROM OLD.location_id
       OR NEW.batch_id IS DISTINCT FROM OLD.batch_id
       OR NEW.device_seq IS DISTINCT FROM OLD.device_seq
       OR NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.event_type IS DISTINCT FROM OLD.event_type
       OR NEW.reason IS DISTINCT FROM OLD.reason
       OR NEW.detail IS DISTINCT FROM OLD.detail
       OR NEW.received_at IS DISTINCT FROM OLD.received_at
       -- Prevent changing numbering association once established
       OR NEW.series_id IS DISTINCT FROM OLD.series_id
       OR NEW.doc_number IS DISTINCT FROM OLD.doc_number THEN
        RAISE EXCEPTION 'sync_quarantine.identity_immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.resolved_at IS NULL THEN
        -- The one resolution: every resolution column set, the raw event as it was.
        IF NEW.resolved_at IS NULL OR NEW.resolution IS NULL OR NEW.resolution_reason IS NULL
           OR NEW.raw_event IS DISTINCT FROM OLD.raw_event THEN
            RAISE EXCEPTION 'sync_quarantine.resolution_incomplete' USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        -- Resolved once: the resolution never changes again; only the raw event may go.
        IF NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
           OR NEW.resolved_by_user_id IS DISTINCT FROM OLD.resolved_by_user_id
           OR NEW.resolution IS DISTINCT FROM OLD.resolution
           OR NEW.resolution_reason IS DISTINCT FROM OLD.resolution_reason THEN
            RAISE EXCEPTION 'sync_quarantine.already_resolved' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.raw_event IS NOT NULL AND NEW.raw_event IS DISTINCT FROM OLD.raw_event THEN
            RAISE EXCEPTION 'sync_quarantine.raw_event_immutable' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION kernel.numbering_gaps()
RETURNS TABLE (
    series_id uuid,
    prefix varchar(24),
    expected_count bigint,
    found_count bigint,
    first_missing bigint
)
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    WITH counted AS (
        SELECT s.series_id,
               s.prefix,
               greatest(s.next_number - 1,
                        (SELECT coalesce(max(d.doc_number), 0) FROM kernel.document d
                          WHERE d.series_id = s.series_id)) AS expected_count,
               (SELECT count(*) FROM kernel.document d WHERE d.series_id = s.series_id) AS found_count,
               s.holder_device_id,
               s.owner_entity_id
          FROM kernel.numbering_series s
    ),
    with_missing AS (
        SELECT c.series_id,
               c.prefix,
               c.expected_count,
               c.found_count,
               (SELECT min(n)
                  FROM generate_series(1, c.expected_count) AS n
                 WHERE NOT EXISTS (SELECT 1 FROM kernel.document d
                                    WHERE d.series_id = c.series_id AND d.doc_number = n)
                   AND NOT EXISTS (SELECT 1 FROM kernel.sync_quarantine q
                                    WHERE q.series_id = c.series_id
                                      AND q.doc_number = n
                                      AND q.device_id = c.holder_device_id
                                      AND q.owner_entity_id = c.owner_entity_id
                                      AND q.resolution IS DISTINCT FROM 'REPAIRED'
                                   )) AS first_missing
          FROM counted c
         WHERE c.expected_count > 0
           AND c.found_count <> c.expected_count
    )
    SELECT w.series_id,
           w.prefix,
           w.expected_count,
           w.found_count,
           w.first_missing
      FROM with_missing w
     WHERE w.first_missing IS NOT NULL;
$$;
