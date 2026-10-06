-- Wave 2 of the code review (RLS-17, the schema rule of
-- docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md (1)), decided 6 October 2026 on the
-- architect's delegation: docs/progress/deviations/2026-10-06-wave2-last-definer-functions-17.md.
--
-- kernel.change_log_append (V0080, search_path fixed by V0086) is a SECURITY DEFINER function executable
-- by app_rw that writes the change log of any shop it is named, and it tested no class: a
-- FEDERATION_VIEW, EXTERNAL_TIMEBOXED or NONE session could bump a shop's snapshot version and write
-- rows into its log. A publication is a write, and only the OWN class writes (doc 18 section 3.7). Its
-- one caller, M7's CustomerChangeLogFanOut through JdbcChangeLog, runs in the event owner's OWN scope
-- (EventConsumerDispatcher.applyScope). The owner and the location stay parameters: the ChangeLog contract
-- (kernel.api.ChangeLog.Target) lets a publisher name the owner and the shop it publishes to. The body
-- is V0080's with the class test first.
CREATE OR REPLACE FUNCTION kernel.change_log_append(
    p_owner_entity_id uuid,
    p_location_id uuid,
    p_table_names text[],
    p_row_ids uuid[],
    p_ops text[],
    p_apply_from date,
    p_urgent boolean
)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, kernel, pg_temp
AS $$
DECLARE
    new_version bigint;
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'OWN' THEN
        RAISE EXCEPTION 'change_log_append is a write of the OWN class'
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF p_owner_entity_id IS NULL OR p_location_id IS NULL THEN
        RAISE EXCEPTION 'change_log_append needs an owner entity and a location';
    END IF;
    IF coalesce(array_length(p_row_ids, 1), 0) = 0
       OR array_length(p_row_ids, 1) <> array_length(p_table_names, 1)
       OR array_length(p_row_ids, 1) <> array_length(p_ops, 1) THEN
        RAISE EXCEPTION 'change_log_append needs as many tables and operations as rows';
    END IF;

    INSERT INTO kernel.location_snapshot_version (location_id, owner_entity_id, current_version, updated_at)
    VALUES (p_location_id, p_owner_entity_id, 1, now())
    ON CONFLICT (location_id)
    DO UPDATE SET current_version = kernel.location_snapshot_version.current_version + 1,
                  updated_at = now()
    RETURNING current_version INTO new_version;

    INSERT INTO kernel.change_log (location_id, version, owner_entity_id, table_name, row_id, op, apply_from, urgent)
    SELECT DISTINCT ON (t.table_name, t.row_id)
           p_location_id, new_version, p_owner_entity_id, t.table_name, t.row_id, t.op, p_apply_from,
           coalesce(p_urgent, false)
      FROM unnest(p_table_names, p_row_ids, p_ops) AS t (table_name, row_id, op)
     ORDER BY t.table_name, t.row_id;

    RETURN new_version;
END;
$$;

REVOKE ALL ON FUNCTION kernel.change_log_append(uuid, uuid, text[], uuid[], text[], date, boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.change_log_append(uuid, uuid, text[], uuid[], text[], date, boolean) TO app_rw;
