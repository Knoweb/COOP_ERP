-- ACC-03: Acknowledge a generated journal export.
-- The exported lines and file remain immutable.

ALTER TABLE integration.journal_export
    ADD COLUMN acknowledged_by text,
    ADD COLUMN acknowledged_at timestamptz;

ALTER TABLE integration.journal_export
    ADD CONSTRAINT journal_export_acknowledgement_consistent
    CHECK (
        (
            status = 'ACKNOWLEDGED'
            AND acknowledged_by IS NOT NULL
            AND btrim(acknowledged_by) <> ''
            AND acknowledged_at IS NOT NULL
        )
        OR
        (
            status <> 'ACKNOWLEDGED'
            AND acknowledged_by IS NULL
            AND acknowledged_at IS NULL
        )
    );

-- Only an entity acting in its own scope may acknowledge its export.
CREATE POLICY own_acknowledge
    ON integration.journal_export
    FOR UPDATE TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND status IN ('GENERATED', 'ACKNOWLEDGED')
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
        AND status = 'ACKNOWLEDGED'
    );

GRANT UPDATE (status, acknowledged_by, acknowledged_at)
    ON integration.journal_export TO app_rw;
