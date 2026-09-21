-- ============================================================
-- K-01 Scope and session support
-- ============================================================

CREATE OR REPLACE FUNCTION kernel.granted_entities()
RETURNS uuid[]
LANGUAGE sql
STABLE
AS $$
    SELECT COALESCE(
        NULLIF(
            current_setting('app.granted_entities', true),
            ''
        )::uuid[],
        ARRAY[]::uuid[]
    )
$$;
