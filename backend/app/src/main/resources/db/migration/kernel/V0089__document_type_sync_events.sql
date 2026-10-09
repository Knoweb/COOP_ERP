-- FIX-06: document type to sync event mapping registry
--
-- Only authorized reference-data management mechanisms may change the mappings.
-- Runtime app_rw must not be allowed to alter the trusted registry.

CREATE TABLE kernel.document_type_sync_event (
    doc_type_code varchar(8) NOT NULL REFERENCES kernel.document_type (doc_type_code),
    event_prefix varchar(40) NOT NULL,
    PRIMARY KEY (doc_type_code, event_prefix)
);

ALTER TABLE kernel.document_type_sync_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.document_type_sync_event FORCE ROW LEVEL SECURITY;

-- Reference data belongs to no tenant: every class reads it, only the seed writes it.
CREATE POLICY reference_read ON kernel.document_type_sync_event
    FOR SELECT TO app_rw USING (true);
CREATE POLICY seed_reference ON kernel.document_type_sync_event
    FOR ALL TO app_seed USING (true) WITH CHECK (true);

REVOKE ALL PRIVILEGES ON kernel.document_type_sync_event FROM PUBLIC;
GRANT SELECT ON kernel.document_type_sync_event TO app_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON kernel.document_type_sync_event TO app_seed;

-- Update kernel.numbering_gaps() to enforce the mapping
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
               s.owner_entity_id,
               s.doc_type_code
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
                                      AND EXISTS (
                                          SELECT 1 FROM kernel.document_type_sync_event dte
                                           WHERE dte.doc_type_code = c.doc_type_code
                                             AND left(q.event_type, length(dte.event_prefix) + 2) = dte.event_prefix || '.v'
                                             AND substring(q.event_type FROM length(dte.event_prefix) + 3) ~ '^[0-9]+$'
                                      )
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
