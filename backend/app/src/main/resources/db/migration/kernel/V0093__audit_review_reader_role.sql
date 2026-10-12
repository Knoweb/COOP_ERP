-- Wave 4 of the code review, M3R-08 and M3R-09 (CR-19A-14). V0091 let the two review functions
-- read the audit log by giving app_seed SELECT on kernel.audit_event and every partition with a
-- policy USING (true): the migrator login, and every other member of app_seed, could then read
-- every tenant's audit rows directly, not only through the functions. Here the functions get an
-- owner of their own, kernel_audit_reader, a NOLOGIN role that nobody logs in as and nobody
-- inherits, which alone holds the read; app_seed's grant and policies go. And the review codes
-- leave the kernel: list_audit_reviews takes the event types from its caller (M3 passes its own),
-- and answers only those that are REVIEW types.
--
-- The index V0091 built on (subject_id, event_type_code) stays. It was built without
-- CONCURRENTLY on the partitioned log, which locks out audit inserts while it builds; V0091 has
-- run on every database (no production database exists yet) and a merged migration is never
-- edited, so nothing is left to build concurrently here (recorded in
-- docs/progress/deviations/2026-10-13-wave4-m3-pricing-reviews.md).

-- ---- the role ---------------------------------------------------------------------------------
-- A group role, not a login (AGENTS.md: a login is the role script's work). On PostgreSQL 16 the
-- migrator (CREATEROLE) may change a function's owner only to a role it may SET ROLE to, so the
-- role is created with createrole_self_grant = 'set': the migrator receives SET, not INHERIT, on
-- it, so it never reads the log through the role's grant by mere membership. Roles are
-- cluster-wide: a second database of the same cluster finds the role in place.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kernel_audit_reader') THEN
        PERFORM set_config('createrole_self_grant', 'set', true);
        CREATE ROLE kernel_audit_reader NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA kernel TO kernel_audit_reader;
GRANT EXECUTE ON FUNCTION kernel.scope_class(), kernel.scope_entity(), kernel.scope_location()
    TO kernel_audit_reader;

-- What the functions read: the REVIEW rows, under a policy that repeats their own scope test,
-- and the event-type catalogue for the severity.
GRANT SELECT ON kernel.audit_event TO kernel_audit_reader;
CREATE POLICY audit_review_read ON kernel.audit_event FOR SELECT TO kernel_audit_reader
    USING (kernel.scope_class() = 'FEDERATION_VIEW'
           OR (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity()));
GRANT SELECT ON kernel.audit_event_type TO kernel_audit_reader;
CREATE POLICY audit_review_read ON kernel.audit_event_type FOR SELECT TO kernel_audit_reader
    USING (true);

-- ---- app_seed no longer reads the log ---------------------------------------------------------
DROP POLICY IF EXISTS audit_event_seed_read ON kernel.audit_event;
REVOKE SELECT ON kernel.audit_event FROM app_seed;

DO $$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT c.relname FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'kernel' AND c.relname LIKE 'audit_event_20%' AND c.relkind IN ('r', 'p')
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS audit_event_seed_read ON kernel.%I', rec.relname);
        EXECUTE format('REVOKE SELECT ON kernel.%I FROM app_seed', rec.relname);
    END LOOP;
END
$$;

-- ---- the functions ----------------------------------------------------------------------------
DROP FUNCTION IF EXISTS kernel.list_audit_reviews(text, integer);

-- The REVIEW records of the event types the caller names, in the caller's scope: an OWN caller's
-- entity (and location, when it has one), or every entity for FEDERATION_VIEW; any other class
-- is refused. A code that is not a REVIEW type answers nothing.
CREATE FUNCTION kernel.list_audit_reviews(
    p_event_types text[],
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
       AND a.event_type_code = ANY (coalesce(p_event_types, '{}'::text[]))
       AND (kernel.scope_class() = 'FEDERATION_VIEW' OR a.owner_entity_id = kernel.scope_entity())
       AND (kernel.scope_location() IS NULL OR a.location_id IS NULL OR a.location_id = kernel.scope_location())
     ORDER BY a.occurred_at DESC
     LIMIT greatest(1, least(coalesce(p_limit, 100), 1000));
END;
$$;

REVOKE ALL ON FUNCTION kernel.list_audit_reviews(text[], integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION kernel.list_audit_reviews(text[], integer) TO app_rw;

-- ---- the owner --------------------------------------------------------------------------------
-- A new owner needs CREATE on the schema at the moment of the change, and only then. A later
-- migration that replaces either function does so after SET ROLE kernel_audit_reader (and the
-- same CREATE grant), since only the owner may replace a function.
GRANT CREATE ON SCHEMA kernel TO kernel_audit_reader;
ALTER FUNCTION kernel.list_audit_reviews(text[], integer) OWNER TO kernel_audit_reader;
ALTER FUNCTION kernel.audit_review_exists(text, uuid, text, text) OWNER TO kernel_audit_reader;
REVOKE CREATE ON SCHEMA kernel FROM kernel_audit_reader;
