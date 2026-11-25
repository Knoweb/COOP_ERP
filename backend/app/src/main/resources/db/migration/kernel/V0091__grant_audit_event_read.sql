-- PRC-01: Narrowly-scoped SECURITY DEFINER functions for pricing reviews.
-- The kernel audit log remains insert-only for app_rw; these functions provide
-- controlled, read-only review queries bounded by the session's active scope.

CREATE INDEX IF NOT EXISTS audit_event_subject_type_idx ON kernel.audit_event (subject_id, event_type_code);

-- The audit table has FORCE ROW LEVEL SECURITY. Functions running as coop_migrator (app_seed)
-- need a SELECT policy and grant on the audit table to read rows inside SECURITY DEFINER functions.
-- Role app_rw retains strictly INSERT-only table privileges.
CREATE POLICY audit_event_seed_read ON kernel.audit_event
    FOR SELECT TO app_seed USING (true);
GRANT SELECT ON kernel.audit_event TO app_seed;

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT relname FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'kernel' AND c.relname LIKE 'audit_event_20%' AND c.relkind IN ('r', 'p')
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE schemaname = 'kernel' AND tablename = rec.relname AND policyname = 'audit_event_seed_read') THEN
            EXECUTE format('CREATE POLICY audit_event_seed_read ON kernel.%I FOR SELECT TO app_seed USING (true)', rec.relname);
        END IF;
        EXECUTE format('GRANT SELECT ON kernel.%I TO app_seed', rec.relname);
    END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION kernel.audit_review_exists(
    p_event_type text,
    p_subject_id uuid,
    p_detail_key text DEFAULT NULL,
    p_detail_value text DEFAULT NULL
)
RETURNS boolean
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, kernel, pg_temp
AS $$
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'OWN' AND kernel.scope_class() IS DISTINCT FROM 'FEDERATION_VIEW' THEN
        RAISE EXCEPTION 'audit_review_exists requires OWN or FEDERATION_VIEW scope'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    RETURN EXISTS (
        SELECT 1
          FROM kernel.audit_event a
          JOIN kernel.audit_event_type t ON t.event_type_code = a.event_type_code
         WHERE t.severity = 'REVIEW'
           AND a.event_type_code = p_event_type
           AND (kernel.scope_class() = 'FEDERATION_VIEW' OR a.owner_entity_id = kernel.scope_entity())
           AND (kernel.scope_location() IS NULL OR a.location_id IS NULL OR a.location_id = kernel.scope_location())
           AND a.subject_id = p_subject_id
           AND (p_detail_key IS NULL OR a.after_state ->> p_detail_key = p_detail_value)
    );
END;
$$;

REVOKE ALL ON FUNCTION kernel.audit_review_exists(text, uuid, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.audit_review_exists(text, uuid, text, text) TO app_rw;

CREATE OR REPLACE FUNCTION kernel.list_audit_reviews(
    p_kind text DEFAULT NULL,
    p_limit integer DEFAULT 100
)
RETURNS TABLE (
    audit_id uuid,
    event_type_code text,
    occurred_at timestamptz,
    owner_entity_id uuid,
    subject_id uuid,
    after_state text
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, kernel, pg_temp
AS $$
BEGIN
    IF kernel.scope_class() IS DISTINCT FROM 'OWN' AND kernel.scope_class() IS DISTINCT FROM 'FEDERATION_VIEW' THEN
        RAISE EXCEPTION 'list_audit_reviews requires OWN or FEDERATION_VIEW scope'
            USING ERRCODE = 'insufficient_privilege';
    END IF;

    RETURN QUERY
    SELECT a.audit_id,
           a.event_type_code::text,
           a.occurred_at,
           a.owner_entity_id,
           a.subject_id,
           a.after_state::text
      FROM kernel.audit_event a
      JOIN kernel.audit_event_type t ON t.event_type_code = a.event_type_code
     WHERE t.severity = 'REVIEW'
       AND a.event_type_code IN ('BATCH_MRP_BELOW_LIST', 'TRADE_ABOVE_MRP', 'LIST_ABOVE_CEILING_AT_GAZETTE')
       AND (kernel.scope_class() = 'FEDERATION_VIEW' OR a.owner_entity_id = kernel.scope_entity())
       AND (kernel.scope_location() IS NULL OR a.location_id IS NULL OR a.location_id = kernel.scope_location())
       AND (p_kind IS NULL OR a.event_type_code = p_kind)
     ORDER BY a.occurred_at DESC
     LIMIT greatest(1, least(coalesce(p_limit, 100), 1000));
END;
$$;

REVOKE ALL ON FUNCTION kernel.list_audit_reviews(text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.list_audit_reviews(text, integer) TO app_rw;
