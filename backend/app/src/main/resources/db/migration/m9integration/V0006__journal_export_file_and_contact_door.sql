-- Wave 2 of the code review (M9-02, M9-03, M9-05, M9-06, RLS-06, RLS-13), decided 6 October 2026 on
-- the architect's delegation: docs/progress/deviations/2026-10-06-wave2-journal-export.md (1),
-- 2026-10-06-wave2-buyer-postings.md (2) and (3), 2026-10-06-wave2-cross-tenant-functions.md (4),
-- 2026-10-06-wave2-member-identity-visibility.md (1); CR-29-1, CR-19A-13.

-- ---- 1. journal_posting: the owner is part of the key (buyer-postings (3)) -------------------------

-- The seller's and the buyer's postings of one invoice share its document id and are two entities'
-- books; with (document_id, seq) alone the buyer's first posting would collide with the seller's.
-- The handler already dedupes by (owner_entity_id, document_id); the key says the same.
ALTER TABLE integration.journal_posting
    DROP CONSTRAINT journal_posting_document_seq_uq;
ALTER TABLE integration.journal_posting
    ADD CONSTRAINT journal_posting_owner_document_seq_uq UNIQUE (owner_entity_id, document_id, seq);

-- ---- 2. journal_export_file: the bytes the accounting package imported (journal-export (1)) -------

-- The generated file is stored, in the export's own transaction, with the version of the writer that
-- made it and the hash of the stored bytes; a download serves these bytes, and the reconciliation
-- checks them against the recorded hash and regenerates them with the writer of format_version.
-- Insert only. An export made before this migration has no row: it is served by the version-1
-- writer, frozen (JournalFileV1).
CREATE TABLE integration.journal_export_file (
    export_id       uuid     PRIMARY KEY REFERENCES integration.journal_export,
    format_version  smallint NOT NULL CHECK (format_version > 0),
    content         bytea    NOT NULL,
    content_hash    char(64) NOT NULL,
    owner_entity_id uuid     NOT NULL
);

ALTER TABLE integration.journal_export_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.journal_export_file FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON integration.journal_export_file FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.journal_export_file FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.journal_export_file FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.journal_export_file FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON integration.journal_export_file FROM PUBLIC;
GRANT SELECT, INSERT ON integration.journal_export_file TO app_rw;

-- ---- 3. the contact door knows the caller (cross-tenant-functions (4); M9-06, RLS-06) -------------

-- V0003's resolver answered any app_rw session the addresses of any entity. Under the pattern of
-- RLS_POLICY_TEMPLATE.md ("a function that answers across tenants") it now answers an OWN caller
-- only, for its own entity or for one it trades with: party.caller_trades_with (m1party V0015) is
-- M1's published fact, ACTIVE or SUSPENDED in either direction, since a suspended buyer still owes
-- and must still be told. FEDERATION_VIEW and EXTERNAL get nothing. The search_path ends in pg_temp
-- (RLS-13; kernel V0086 says why) and names the schema the body reads.
CREATE OR REPLACE FUNCTION integration.notification_recipients(p_entity uuid, p_role text)
    RETURNS TABLE (channel text, address text, language text)
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = pg_catalog, integration, pg_temp
AS $$
    SELECT c.channel, c.address, btrim(c.language)
      FROM integration.notification_contact c
     WHERE kernel.scope_class() = 'OWN'
       AND kernel.scope_entity() IS NOT NULL
       AND p_entity IS NOT NULL
       AND (p_entity = kernel.scope_entity() OR party.caller_trades_with(p_entity))
       AND c.owner_entity_id = p_entity
       AND c.role_code = p_role
       AND c.status = 'ACTIVE'
     ORDER BY c.channel, c.contact_id
$$;

REVOKE ALL ON FUNCTION integration.notification_recipients(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION integration.notification_recipients(uuid, text) TO app_rw;

-- ---- 4. an address is personal data (member-identity-visibility (1)) -----------------------------

-- FEDERATION_VIEW reads no personal data of natural persons by policy; the Federation's view of
-- contacts goes with it. The matrix records "federation and external read nothing" for the table.
DROP POLICY fed_view ON integration.notification_contact;
