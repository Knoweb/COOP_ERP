-- V0009__expiry_tasks.sql: Expiry review tasks (doc 25 section 4.6; 25A section 3).
-- Created when a batch enters its configured expiry warning window at a location.
-- Multiple expiry tasks at one location can link to a single count task.

CREATE TABLE inventory.expiry_task (
    task_id               uuid          PRIMARY KEY,
    owner_entity_id       uuid          NOT NULL,
    location_id           uuid          NOT NULL,
    batch_id              uuid          NOT NULL,
    sku_id                uuid          NOT NULL,
    days_to_expiry        smallint      NOT NULL,
    qty_at_open           numeric(14,3) NOT NULL,
    status                text          NOT NULL DEFAULT 'OPEN'
                                        CHECK (status IN ('OPEN', 'COUNTED', 'CLOSED')),
    count_task_id         uuid          REFERENCES inventory.count_task (task_id),
    suggested_writeoff_id uuid,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    closed_at             timestamptz
);

-- At most one open/active expiry task per location and batch.
CREATE UNIQUE INDEX expiry_task_open_per_location_batch
    ON inventory.expiry_task (location_id, batch_id)
 WHERE status <> 'CLOSED';

CREATE INDEX expiry_task_by_location_status
    ON inventory.expiry_task (location_id, status);

-- ---------------------------------------------------------------------------------------------
-- Row-level security: 17A section 6.3 template.
ALTER TABLE inventory.expiry_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.expiry_task FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.expiry_task FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON inventory.expiry_task FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_update ON inventory.expiry_task FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON inventory.expiry_task FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON inventory.expiry_task FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.expiry_task TO app_rw;
GRANT UPDATE (status, count_task_id, suggested_writeoff_id, closed_at)
    ON inventory.expiry_task TO app_rw;
