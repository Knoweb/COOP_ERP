-- K-08 decisions of 27 September 2026 (branch feat/decisions-sync), lane D (V0080 to V0099,
-- kernel/README.md). Two of the part-1 deferrals that do not wait for M4:
--
--   sync_sequence_gap          the audited sequence reset of doc 32 section 8: a device that cannot
--                              produce expected_seq (its outbox was lost) is moved on by an
--                              administrator with a reason code; the numbers it skips are recorded
--                              here as a documented gap (doc 32 section 7, "missing events remain a
--                              documented gap"; doc 18 E-I3). Insert-only, like every ledger.
--   change_log purge           doc 32 DR-4: the change log keeps sync.change_log.retention (30 days)
--                              and older entries are removed by the nightly job, through the
--                              function below; location_snapshot_version remembers up to which
--                              version a shop's log was purged, so a till whose version is older
--                              takes a full snapshot instead of a delta with holes.

CREATE TABLE kernel.sync_sequence_gap (
    gap_id uuid PRIMARY KEY,
    device_id uuid NOT NULL,
    owner_entity_id uuid NOT NULL,
    -- The sequence numbers central will never receive: the cursor stood at from_seq - 1 and now
    -- stands at to_seq, so the device's next batch starts at to_seq + 1.
    from_seq bigint NOT NULL
        CHECK (from_seq >= 1),
    to_seq bigint NOT NULL,
    reason_code varchar(40) NOT NULL,
    reason_text text,
    recorded_by_user_id uuid NOT NULL,
    -- The operation's Idempotency-Key: a retry of the same reset is answered with this row.
    idempotency_key varchar(200) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CHECK (to_seq >= from_seq)
);

CREATE UNIQUE INDEX sync_sequence_gap_key_idx
    ON kernel.sync_sequence_gap (device_id, idempotency_key);

CREATE INDEX sync_sequence_gap_device_idx
    ON kernel.sync_sequence_gap (device_id, from_seq);

ALTER TABLE kernel.sync_sequence_gap ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.sync_sequence_gap FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.sync_sequence_gap FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON kernel.sync_sequence_gap FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.sync_sequence_gap FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.sync_sequence_gap FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.sync_sequence_gap FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.sync_sequence_gap TO app_rw;


-- ---- the change-log purge ------------------------------------------------------------------

ALTER TABLE kernel.location_snapshot_version
    ADD COLUMN purged_through_version bigint NOT NULL DEFAULT 0
        CHECK (purged_through_version >= 0);

COMMENT ON COLUMN kernel.location_snapshot_version.purged_through_version IS
    'The change log of this location holds no complete record of versions up to here: a device at an older version takes a full snapshot';

-- The purge deletes as the function's owner (the migrator, a member of app_seed). FORCE ROW
-- LEVEL SECURITY binds the owner too, so the delete needs a policy of its own; app_seed holds no
-- DELETE grant, so the function stays the only way to remove a row.
CREATE POLICY change_log_purge ON kernel.change_log
    FOR DELETE TO app_seed USING (true);

-- Removes the change-log entries recorded before p_before, per location, and records how far each
-- location's log was purged. Only whole versions go: the log of a location is cut at the first
-- version that still has an entry younger than p_before, so a delta never starts inside a
-- publication. One entry is kept although it is old: the latest entry of a row whose apply_from
-- is on or after p_keep_apply_from, because the full snapshot takes a row's apply_from from the
-- log (a price dated two months ahead must still be held for its day on a till that downloads
-- the full snapshot today). Each location's version row is locked while its log is cut, so a
-- publication for that shop waits and the cut cannot race it. Returns the number of entries
-- removed.
CREATE OR REPLACE FUNCTION kernel.change_log_purge(
    p_before timestamptz,
    p_keep_apply_from date
)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, kernel
AS $$
DECLARE
    shop record;
    first_young bigint;
    current_v bigint;
    through bigint;
    removed bigint;
    total bigint := 0;
BEGIN
    -- The register's lower bound on sync.change_log.retention is 24 hours; nobody purges closer.
    IF p_before IS NULL OR p_before > now() - interval '24 hours' THEN
        RAISE EXCEPTION 'change_log_purge keeps at least the last 24 hours of the change log';
    END IF;
    IF p_keep_apply_from IS NULL THEN
        RAISE EXCEPTION 'change_log_purge needs the business date from which apply_from is kept';
    END IF;

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
                    AND c.apply_from >= p_keep_apply_from
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

REVOKE ALL ON FUNCTION kernel.change_log_purge(timestamptz, date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.change_log_purge(timestamptz, date) TO app_rw;
