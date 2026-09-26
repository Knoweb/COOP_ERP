-- K-07, the findings of the review of 26 September (doc 18 part C, C-I1 and C-I2; 19A section 7;
-- RLS_POLICY_TEMPLATE.md). Lane C. V0050 is merged and is not edited: everything here alters
-- what it created.
--
-- 1. Nothing joins an issued document. V0050 froze the header (trigger) and the lines (no
--    UPDATE) but let the owner INSERT a new line under an issued header, which its totals
--    and hash do not cover. A BEFORE INSERT trigger now refuses it for everybody, and the
--    write policy refuses it for the application user. The issuance protocol therefore stores
--    the lines before it sets issued_at, and a till's document arriving through ingestion
--    (K-08 part 2, origin OFFLINE) takes the same path: header without issued_at, lines,
--    then the one update that sets number, hash and issued_at.
-- 2. REVERSES is at most once per original by a partial unique index, not only by a check the
--    application makes before it inserts (two concurrent reversals both passed it).
-- 3. A shop-scoped session sees and advances the ENTITY series (location_id IS NULL) and reads
--    the entity's location-less documents: the template's location line, copied as it was,
--    made "location_id = scope_location()" false for every NULL location, so a GRN or an ADJ
--    issued at a shop without a LOCATION series failed with document.series_missing although
--    the entity series existed (24B: ORD and DISC are ENTITY series). The matrix test lists
--    both tables as a departure from the template with this reason.
-- 4. party_read applies the location line on the owner's side only (CR-17A-3; the template's
--    text), so a shop-scoped counterparty sees the documents it is a side of.
-- 5. numbering_gaps() measures a series against the larger of its counter and its highest
--    number, so a series whose documents arrived through ingestion (counter still 1) is
--    checked too.

-- ---- 1. no line joins an issued document ------------------------------------------------------

CREATE OR REPLACE FUNCTION kernel.document_line_after_issue()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM kernel.document d
                WHERE d.document_id = NEW.document_id AND d.issued_at IS NOT NULL) THEN
        RAISE EXCEPTION 'document.immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_document_line_after_issue
    BEFORE INSERT ON kernel.document_line
    FOR EACH ROW EXECUTE FUNCTION kernel.document_line_after_issue();

-- The same rule for the application user, in the policy, so that it holds whatever the
-- trigger's fate: SECURITY INVOKER, the header is read under the caller's own policies.
CREATE OR REPLACE FUNCTION kernel.document_unissued(p_document_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    SELECT EXISTS (SELECT 1
                     FROM kernel.document d
                    WHERE d.document_id = p_document_id
                      AND d.issued_at IS NULL)
$$;

ALTER POLICY document_write ON kernel.document_line
    WITH CHECK (kernel.document_owned(document_id) AND kernel.document_unissued(document_id));

-- ---- 2. REVERSES at most once per original -----------------------------------------------------

CREATE UNIQUE INDEX document_link_reverses_once_idx
    ON kernel.document_link (to_document_id)
    WHERE link_type = 'REVERSES';

-- ---- 3. the ENTITY series and location-less documents in a shop-scoped session ----------------

ALTER POLICY own_read ON kernel.numbering_series
    USING (kernel.scope_class() = 'OWN'
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
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

ALTER POLICY own_read ON kernel.document
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL
                OR location_id IS NULL
                OR location_id = kernel.scope_location()));

ALTER POLICY own_update ON kernel.document
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL
                OR location_id IS NULL
                OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

-- ---- 4. party_read: the location on the owner's side only ------------------------------------

ALTER POLICY party_read ON kernel.document
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND ((owner_entity_id = kernel.scope_entity()
                 AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
                OR counterparty_entity_id = kernel.scope_entity()));

-- ---- 5. density against the counter and the highest number -----------------------------------
--
-- expected_count is the larger of next_number - 1 (what central handed out) and the highest
-- doc_number stored (what arrived). A series with no document and a counter at 1 is left out.
-- The density of (source, source_seq) that 19A names beside doc_number waits for the ingestion
-- of K-08 part 2, which writes those columns; until then the check is doc_number only.
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
               (SELECT count(*) FROM kernel.document d WHERE d.series_id = s.series_id) AS found_count
          FROM kernel.numbering_series s
    )
    SELECT c.series_id,
           c.prefix,
           c.expected_count,
           c.found_count,
           (SELECT min(n)
              FROM generate_series(1, c.expected_count) AS n
             WHERE NOT EXISTS (SELECT 1 FROM kernel.document d
                                WHERE d.series_id = c.series_id AND d.doc_number = n)) AS first_missing
      FROM counted c
     WHERE c.expected_count > 0
       AND c.found_count <> c.expected_count
$$;
