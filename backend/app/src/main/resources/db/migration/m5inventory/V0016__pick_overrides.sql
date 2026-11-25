-- INV-05: a manual FEFO override is a new fact. The suggested pick line remains immutable;
-- reservation and dispatch resolve the replacement lot through this row.

ALTER TABLE inventory.pick_list_line
    ADD CONSTRAINT pick_line_override_identity_uq
        UNIQUE (pick_line_id, pick_list_id, owner_entity_id, location_id, stock_lot_id, sku_id);

ALTER TABLE inventory.stock_lot
    ADD CONSTRAINT stock_lot_override_identity_uq
        UNIQUE (stock_lot_id, owner_entity_id, location_id, sku_id, batch_id, condition);

CREATE TABLE inventory.pick_override (
    override_id              uuid          PRIMARY KEY,
    pick_line_id             uuid          NOT NULL UNIQUE,
    pick_list_id             uuid          NOT NULL,
    owner_entity_id          uuid          NOT NULL,
    location_id              uuid          NOT NULL,
    sku_id                   uuid          NOT NULL,
    original_stock_lot_id    uuid          NOT NULL,
    replacement_stock_lot_id uuid          NOT NULL,
    replacement_batch_id     uuid          NOT NULL,
    condition                text          NOT NULL DEFAULT 'GOOD' CHECK (condition = 'GOOD'),
    qty                      numeric(14,3) NOT NULL CHECK (qty > 0),
    reason                   varchar(500)  NOT NULL CHECK (btrim(reason) <> ''),
    overridden_by            uuid          NOT NULL,
    overridden_at            timestamptz   NOT NULL,
    CHECK (original_stock_lot_id <> replacement_stock_lot_id),
    CONSTRAINT pick_override_line_fk
        FOREIGN KEY (pick_line_id, pick_list_id, owner_entity_id, location_id, original_stock_lot_id, sku_id)
        REFERENCES inventory.pick_list_line
            (pick_line_id, pick_list_id, owner_entity_id, location_id, stock_lot_id, sku_id),
    CONSTRAINT pick_override_replacement_fk
        FOREIGN KEY
            (replacement_stock_lot_id, owner_entity_id, location_id, sku_id, replacement_batch_id, condition)
        REFERENCES inventory.stock_lot
            (stock_lot_id, owner_entity_id, location_id, sku_id, batch_id, condition)
);

CREATE INDEX pick_override_by_list ON inventory.pick_override (pick_list_id);
CREATE INDEX pick_override_by_replacement ON inventory.pick_override (replacement_stock_lot_id);

ALTER TABLE inventory.pick_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.pick_override FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.pick_override FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.pick_override FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.pick_override FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.pick_override FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.pick_override TO app_rw;
