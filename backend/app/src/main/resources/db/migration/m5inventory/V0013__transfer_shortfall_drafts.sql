-- INV-07 Phase 2B: a short receipt records its discrepancy as a destination-owned draft.
-- This is deliberately not an issued kernel ADJ document: approval, posting and accounting are
-- pending business decisions. Creating this draft must never create a stock movement.

ALTER TABLE inventory.transfer_receipt_line
    ADD CONSTRAINT transfer_receipt_line_shortfall_identity
    UNIQUE (transfer_id, transfer_line_id, owner_entity_id, location_id, from_location_id);

CREATE TABLE inventory.transfer_shortfall (
    shortfall_id      uuid        PRIMARY KEY,
    transfer_id       uuid        NOT NULL UNIQUE,
    owner_entity_id   uuid        NOT NULL,
    location_id       uuid        NOT NULL,
    from_location_id  uuid        NOT NULL,
    category          text        NOT NULL CHECK (category = 'DAMAGED_IN_TRANSIT'),
    status            text        NOT NULL CHECK (status = 'DRAFT'),
    created_by        uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shortfall_id, transfer_id, owner_entity_id, location_id, from_location_id),
    FOREIGN KEY (transfer_id, owner_entity_id, location_id, from_location_id)
        REFERENCES inventory.transfer_receipt
            (transfer_id, owner_entity_id, location_id, from_location_id)
);

CREATE TABLE inventory.transfer_shortfall_line (
    shortfall_id      uuid          NOT NULL,
    transfer_id       uuid          NOT NULL,
    transfer_line_id  uuid          NOT NULL,
    owner_entity_id   uuid          NOT NULL,
    location_id       uuid          NOT NULL,
    from_location_id  uuid          NOT NULL,
    shortfall_qty     numeric(14,3) NOT NULL CHECK (shortfall_qty > 0),
    unit_cost         numeric(14,4) NOT NULL CHECK (unit_cost >= 0),
    PRIMARY KEY (shortfall_id, transfer_line_id),
    UNIQUE (transfer_id, transfer_line_id),
    FOREIGN KEY (shortfall_id, transfer_id, owner_entity_id, location_id, from_location_id)
        REFERENCES inventory.transfer_shortfall
            (shortfall_id, transfer_id, owner_entity_id, location_id, from_location_id),
    FOREIGN KEY (transfer_id, transfer_line_id, owner_entity_id, location_id, from_location_id)
        REFERENCES inventory.transfer_receipt_line
            (transfer_id, transfer_line_id, owner_entity_id, location_id, from_location_id)
);

CREATE INDEX transfer_shortfall_by_destination
    ON inventory.transfer_shortfall (owner_entity_id, location_id, created_at);

ALTER TABLE inventory.transfer_shortfall ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_shortfall FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.transfer_shortfall FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY source_read ON inventory.transfer_shortfall FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND from_location_id = kernel.scope_location());
CREATE POLICY own_write ON inventory.transfer_shortfall FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.transfer_shortfall FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.transfer_shortfall FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.transfer_shortfall TO app_rw;

ALTER TABLE inventory.transfer_shortfall_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_shortfall_line FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.transfer_shortfall_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY source_read ON inventory.transfer_shortfall_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND from_location_id = kernel.scope_location());
CREATE POLICY own_write ON inventory.transfer_shortfall_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.transfer_shortfall_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.transfer_shortfall_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.transfer_shortfall_line TO app_rw;
