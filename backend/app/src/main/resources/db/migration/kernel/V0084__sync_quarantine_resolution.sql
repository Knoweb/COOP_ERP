-- Wave 2, PR 03 (decided 6 October 2026 on the architect's delegation:
-- docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (5); CR-32-1 item 2).
--
-- A quarantined fact is resolved, never purged by time (doc 32 S4: "never lost"). An MPCS
-- administrator marks a row REPAIRED (the till resent a correct copy, doc 32 section 8) or
-- DISCARDED, with a reason, once (ResolveQuarantineHandler, sync.quarantine.resolve, audited,
-- sync.quarantine.resolved.v1). The raw event of a resolved row is nulled a retention after the
-- resolution (QuarantineRawRetentionJob, sync.quarantine.raw_retention_days); the row stays as
-- the record that a fact was refused and why.
--
-- The object_upload pattern (kernel V0060): app_rw may change the four resolution columns and
-- nothing else, and a trigger lets a row be resolved once. The raw event is nulled only through
-- kernel.sync_quarantine_drop_raw, a SECURITY DEFINER function run by the nightly job across
-- every entity, as kernel.change_log_purge (V0082) is; app_rw holds no UPDATE on raw_event.

ALTER TABLE kernel.sync_quarantine
    ADD COLUMN resolved_at timestamptz,
    ADD COLUMN resolved_by_user_id uuid,
    ADD COLUMN resolution text CHECK (resolution IN ('REPAIRED', 'DISCARDED')),
    ADD COLUMN resolution_reason text CHECK (length(resolution_reason) <= 600),
    ALTER COLUMN raw_event DROP NOT NULL,
    ADD CONSTRAINT sync_quarantine_resolved
        CHECK ((resolved_at IS NULL) = (resolution IS NULL)
               AND (resolved_at IS NULL) = (resolution_reason IS NULL)),
    -- Only a resolved row may lose its raw event.
    ADD CONSTRAINT sync_quarantine_raw_kept_until_resolved
        CHECK (raw_event IS NOT NULL OR resolved_at IS NOT NULL);

COMMENT ON COLUMN kernel.sync_quarantine.raw_event IS
    'The event as it arrived (the values of forbidden fields replaced by [removed]); null once resolved and past sync.quarantine.raw_retention_days';

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
       OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
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

CREATE TRIGGER trg_sync_quarantine_transition
    BEFORE UPDATE ON kernel.sync_quarantine
    FOR EACH ROW EXECUTE FUNCTION kernel.sync_quarantine_transition();

-- The resolution is the society's, at its shop when the administrator acts at one (the template's
-- own_update with own_read's location line, as V0083 gave own_write).
CREATE POLICY own_update ON kernel.sync_quarantine FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

GRANT UPDATE (resolved_at, resolved_by_user_id, resolution, resolution_reason)
    ON kernel.sync_quarantine TO app_rw;

-- The retention of the raw event runs as the function's owner (the migrator, a member of
-- app_seed). FORCE ROW LEVEL SECURITY binds the owner too, so the update needs policies of its
-- own, and only for resolved rows; app_seed holds no grant, so the function stays the only way.
CREATE POLICY raw_retention_read ON kernel.sync_quarantine
    FOR SELECT TO app_seed USING (resolved_at IS NOT NULL);
CREATE POLICY raw_retention ON kernel.sync_quarantine
    FOR UPDATE TO app_seed USING (resolved_at IS NOT NULL) WITH CHECK (resolved_at IS NOT NULL);

-- Nulls the raw event of every row resolved before p_resolved_before and returns how many. Run
-- by the nightly job in the platform's federation-wide scope, and refused in any other: a
-- society's session has no business across entities.
CREATE OR REPLACE FUNCTION kernel.sync_quarantine_drop_raw(p_resolved_before timestamptz)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, kernel, pg_temp
AS $$
DECLARE
    dropped bigint;
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'FEDERATION_VIEW' THEN
        RAISE EXCEPTION 'sync_quarantine_drop_raw runs in the platform''s federation-wide scope only'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_resolved_before IS NULL OR p_resolved_before > now() THEN
        RAISE EXCEPTION 'sync_quarantine_drop_raw drops only what was resolved in the past';
    END IF;
    UPDATE kernel.sync_quarantine
       SET raw_event = NULL
     WHERE resolved_at IS NOT NULL
       AND resolved_at < p_resolved_before
       AND raw_event IS NOT NULL;
    GET DIAGNOSTICS dropped = ROW_COUNT;
    RETURN dropped;
END;
$$;

REVOKE ALL ON FUNCTION kernel.sync_quarantine_drop_raw(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.sync_quarantine_drop_raw(timestamptz) TO app_rw;
