-- INV-07 Phase 1: persist the actual quantities of a receipt and reserve an insert-only fact for
-- cancellation. Public short-receipt and cancellation commands are deliberately not activated by
-- this migration.

-- Composite identities let the database prove that a receipt line belongs to the same transfer,
-- entity, source and destination as both its receipt and its immutable issued line.
ALTER TABLE inventory.transfer_receipt
    ADD CONSTRAINT transfer_receipt_identity
    UNIQUE (transfer_id, owner_entity_id, location_id, from_location_id);

ALTER TABLE inventory.transfer_line
    ADD CONSTRAINT transfer_line_receipt_identity
    UNIQUE (transfer_id, line_id, owner_entity_id, location_id, to_location_id);

CREATE TABLE inventory.transfer_receipt_line (
    transfer_id       uuid          NOT NULL,
    transfer_line_id  uuid          NOT NULL,
    owner_entity_id   uuid          NOT NULL,
    -- The receipt and its lines belong to the destination.
    location_id       uuid          NOT NULL,
    from_location_id  uuid          NOT NULL,
    received_qty      numeric(14,3) NOT NULL CHECK (received_qty >= 0),
    PRIMARY KEY (transfer_id, transfer_line_id),
    FOREIGN KEY (transfer_id, owner_entity_id, location_id, from_location_id)
        REFERENCES inventory.transfer_receipt
            (transfer_id, owner_entity_id, location_id, from_location_id),
    FOREIGN KEY (transfer_id, transfer_line_id, owner_entity_id, from_location_id, location_id)
        REFERENCES inventory.transfer_line
            (transfer_id, line_id, owner_entity_id, location_id, to_location_id)
);

-- Existing receipts pre-date per-line actuals and represent full receipts. Their only truthful
-- historical quantity is therefore the immutable quantity on each issued transfer line. FORCE RLS
-- also binds a table owner, while Flyway carries no tenant scope, so lift it only for these reads
-- and restore it in this same transactional migration.
ALTER TABLE inventory.transfer_receipt NO FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_line NO FORCE ROW LEVEL SECURITY;

INSERT INTO inventory.transfer_receipt_line
    (transfer_id, transfer_line_id, owner_entity_id, location_id, from_location_id, received_qty)
SELECT r.transfer_id, l.line_id, r.owner_entity_id, r.location_id, r.from_location_id, l.qty
  FROM inventory.transfer_receipt r
  JOIN inventory.transfer_line l
    ON l.transfer_id = r.transfer_id
   AND l.owner_entity_id = r.owner_entity_id
   AND l.location_id = r.from_location_id
   AND l.to_location_id = r.location_id;

ALTER TABLE inventory.transfer_line FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_receipt FORCE ROW LEVEL SECURITY;

ALTER TABLE inventory.transfer_receipt_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_receipt_line FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.transfer_receipt_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY source_read ON inventory.transfer_receipt_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND from_location_id = kernel.scope_location());
CREATE POLICY own_write ON inventory.transfer_receipt_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.transfer_receipt_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.transfer_receipt_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.transfer_receipt_line TO app_rw;

-- A cancellation is a future source-owned terminal fact. This phase creates its safe persistence
-- boundary only; no command writes it yet.
CREATE TABLE inventory.transfer_cancellation (
    transfer_id      uuid        PRIMARY KEY,
    owner_entity_id  uuid        NOT NULL,
    location_id      uuid        NOT NULL,
    to_location_id   uuid        NOT NULL,
    cancelled_by     uuid,
    cancelled_at     timestamptz NOT NULL DEFAULT now(),
    reason           text        NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
    CHECK (to_location_id <> location_id),
    FOREIGN KEY (transfer_id, owner_entity_id, location_id, to_location_id)
        REFERENCES inventory.transfer (transfer_id, owner_entity_id, location_id, to_location_id)
);

CREATE INDEX transfer_cancellation_by_destination
    ON inventory.transfer_cancellation (to_location_id, cancelled_at);

ALTER TABLE inventory.transfer_cancellation ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_cancellation FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.transfer_cancellation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY dest_read ON inventory.transfer_cancellation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND to_location_id = kernel.scope_location());
CREATE POLICY own_write ON inventory.transfer_cancellation FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.transfer_cancellation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.transfer_cancellation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.transfer_cancellation TO app_rw;
