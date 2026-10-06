-- Wave 2 of the code review (RLS-09's kernel helper, RLS-13, RLS-14), decided 6 October 2026 on
-- the architect's delegation: docs/progress/deviations/2026-10-06-wave2-extension-rows-after-issue.md
-- (2) and 2026-10-06-wave2-cross-tenant-functions.md (1), (2).

-- ---- 1. a document is open for write until the transaction that issues it ends ------------------

-- The extension rows of a document (a module's doc_* table keyed on document_id) are written before
-- the document is issued, or in the transaction that issues it, never later (the rule of
-- RLS_POLICY_TEMPLATE.md, "The rows of a document"). A plain "unissued" test (kernel.document_unissued,
-- V0055) would refuse the nine M4 handlers that issue first and then write their row in the same
-- transaction; for the GRN that order cannot change (the synthetic batch number is S-<GRN number>-<line>,
-- so the batch exists only after the number is drawn). The header's xmin is the id of the transaction
-- that last wrote it: after the issuing UPDATE it equals the current transaction's id, so "issued in
-- this transaction" is a fact the database knows without a session variable (which the template
-- forbids) and without trusting a clock. SECURITY INVOKER: the header is read under the caller's own
-- policies, as document_owned and document_unissued read it.
--
-- pg_current_xact_id() returns xid8; its cast to xid (the type of xmin) exists on PostgreSQL 16, which
-- the kernel's test proves (DocumentBasePostgresIntegrationTest). A subtransaction (a savepoint) gives
-- the tuple the subtransaction's id, not this one: the kernel opens none (Spring's default propagation,
-- and SystemScope.inOwnTransaction opens a connection, not a savepoint).
CREATE OR REPLACE FUNCTION kernel.document_open_for_write(p_document_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    SELECT EXISTS (SELECT 1
                     FROM kernel.document d
                    WHERE d.document_id = p_document_id
                      AND (d.issued_at IS NULL
                           OR d.xmin = pg_current_xact_id()::xid))
$$;

-- ---- 2. the change-log purge knows who is asking (RLS-14) -------------------------------------------

-- The same body as V0082, with two more rules: only the FEDERATION_VIEW class (the class
-- ChangeLogPurgeJob runs in) may purge, an OWN or NONE caller is refused; and the date from which
-- apply_from is kept is clamped to today, so a caller passing a date far ahead cannot drop the
-- future-dated rows a full snapshot takes its apply_from from. The 24-hour floor stays. The retention
-- itself stays the job's argument: reading sync.change_log.retention inside the definer would need a
-- kernel policy for app_seed on config_value and a copy of ConfigRegistry's scope resolution in SQL.
CREATE OR REPLACE FUNCTION kernel.change_log_purge(
    p_before timestamptz,
    p_keep_apply_from date
)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, kernel, pg_temp
AS $$
DECLARE
    shop record;
    first_young bigint;
    current_v bigint;
    through bigint;
    removed bigint;
    total bigint := 0;
    keep_from date;
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'FEDERATION_VIEW' THEN
        RAISE EXCEPTION 'change_log_purge is a job of the FEDERATION_VIEW class'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    -- The register's lower bound on sync.change_log.retention is 24 hours; nobody purges closer.
    IF p_before IS NULL OR p_before > now() - interval '24 hours' THEN
        RAISE EXCEPTION 'change_log_purge keeps at least the last 24 hours of the change log';
    END IF;
    IF p_keep_apply_from IS NULL THEN
        RAISE EXCEPTION 'change_log_purge needs the business date from which apply_from is kept';
    END IF;
    -- A row dated today or later is always kept: the full snapshot still needs its date.
    keep_from := least(p_keep_apply_from, current_date);

    FOR shop IN
        SELECT DISTINCT location_id FROM kernel.change_log WHERE recorded_at < p_before
    LOOP
        SELECT current_version INTO current_v
          FROM kernel.location_snapshot_version
         WHERE location_id = shop.location_id
           FOR UPDATE;

        SELECT min(version) INTO first_young
          FROM kernel.change_log
         WHERE location_id = shop.location_id AND recorded_at >= p_before;

        through := coalesce(first_young - 1, current_v, 0);
        IF through <= 0 THEN
            CONTINUE;
        END IF;

        DELETE FROM kernel.change_log c
         WHERE c.location_id = shop.location_id
           AND c.version <= through
           AND NOT (c.apply_from IS NOT NULL
                    AND c.apply_from >= keep_from
                    AND c.version = (SELECT max(l.version)
                                       FROM kernel.change_log l
                                      WHERE l.location_id = c.location_id
                                        AND l.table_name = c.table_name
                                        AND l.row_id = c.row_id));
        GET DIAGNOSTICS removed = ROW_COUNT;
        total := total + removed;

        UPDATE kernel.location_snapshot_version
           SET purged_through_version = greatest(purged_through_version, through)
         WHERE location_id = shop.location_id;
    END LOOP;

    RETURN total;
END;
$$;

-- ---- 3. definer hygiene (RLS-13) ------------------------------------------------------------------

-- PostgreSQL searches the session's temporary schema before pg_catalog unless pg_temp is named
-- last, so a session could shadow a catalogue relation a definer function reads (pg_inherits,
-- pg_policy) with a temporary table. Every SECURITY DEFINER function of the kernel now ends its
-- search_path in pg_temp (PostgreSQL's own guidance for definer functions); the bodies are unchanged.
-- kernel.sync_quarantine_drop_raw (V0084) already does. 01-roles.sh revokes TEMPORARY on the
-- database from PUBLIC besides.
ALTER FUNCTION kernel.ensure_audit_partitions(integer)
    SET search_path = pg_catalog, kernel, pg_temp;
ALTER FUNCTION kernel.ensure_event_outbox_partitions(integer)
    SET search_path = pg_catalog, kernel, pg_temp;
ALTER FUNCTION kernel.change_log_append(uuid, uuid, text[], uuid[], text[], date, boolean)
    SET search_path = pg_catalog, kernel, pg_temp;
