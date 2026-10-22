-- INV-06 Phase 2B: dispatch is a source-owned, insert-only fact between issue and receipt.
-- Existing transfers pre-date the separate command and were issued-and-dispatched atomically, so
-- backfill them as dispatched at their issue time. This preserves their lifecycle and permits their
-- destination to receive them after this migration.
ALTER TABLE inventory.transfer
    ADD CONSTRAINT transfer_dispatch_identity
    UNIQUE (transfer_id, owner_entity_id, location_id, to_location_id);

CREATE TABLE inventory.transfer_dispatch (
    transfer_id      uuid        PRIMARY KEY,
    owner_entity_id  uuid        NOT NULL,
    -- The source location owns this fact; the destination may read it.
    location_id      uuid        NOT NULL,
    to_location_id   uuid        NOT NULL,
    dispatched_by    uuid,
    dispatched_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (to_location_id <> location_id),
    FOREIGN KEY (transfer_id, owner_entity_id, location_id, to_location_id)
        REFERENCES inventory.transfer (transfer_id, owner_entity_id, location_id, to_location_id)
);

-- The migrator owns inventory.transfer, but FORCE RLS also binds the owner and migrations do not
-- carry a tenant scope. Lift FORCE only for this backfill, then restore it in the same transaction.
ALTER TABLE inventory.transfer NO FORCE ROW LEVEL SECURITY;

INSERT INTO inventory.transfer_dispatch
    (transfer_id, owner_entity_id, location_id, to_location_id, dispatched_by, dispatched_at)
SELECT transfer_id, owner_entity_id, location_id, to_location_id, issued_by, issued_at
  FROM inventory.transfer;

ALTER TABLE inventory.transfer FORCE ROW LEVEL SECURITY;

CREATE INDEX transfer_dispatch_by_destination
    ON inventory.transfer_dispatch (to_location_id, dispatched_at);

ALTER TABLE inventory.transfer_dispatch ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_dispatch FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.transfer_dispatch FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY dest_read ON inventory.transfer_dispatch FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND to_location_id = kernel.scope_location());
CREATE POLICY own_write ON inventory.transfer_dispatch FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.transfer_dispatch FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.transfer_dispatch FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.transfer_dispatch TO app_rw;
