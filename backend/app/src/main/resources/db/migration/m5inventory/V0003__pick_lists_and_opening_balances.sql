-- M5-03 and M5-10 (demo scope): the pick list a delivery note reserves, and the opening balance
-- (25A sections 3, 6.2 and 6.3; doc 25 sections 3.4, 3.7 and 4.7).
--
--   pick_list, pick_list_line        what an issued delivery note reserves at the seller's lots
--                                    (FEFO), until the vehicle is dispatched
--   opening_balance, _line           the counted stock of a location with batch data and cost,
--                                    prepared, signed by the entity, countersigned, then posted
--
-- Departures from the guide's DDL, recorded in the module README: 25A keeps a pick list's lines
-- as jsonb; they are rows here, because availability sums the open reservations per location and
-- SKU. 25A extends the OPB document (doc_opening_balance, keyed on document_id); the draft and its
-- two signatures live here instead and the OPB document is issued when the balance is posted,
-- which is when 25A section 4.7 issues it ("issuance (ENTITY series)" on countersign).

-- ---------------------------------------------------------------------------------------------
-- 1. Pick lists
CREATE TABLE inventory.pick_list (
    pick_list_id         uuid        PRIMARY KEY,
    owner_entity_id      uuid        NOT NULL,
    delivery_document_id uuid        NOT NULL,
    status               text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DISPATCHED')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    dispatched_at        timestamptz,
    CONSTRAINT pick_list_per_delivery_uq UNIQUE (delivery_document_id)
);

ALTER TABLE inventory.pick_list ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.pick_list FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.pick_list FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON inventory.pick_list FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON inventory.pick_list FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON inventory.pick_list FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.pick_list FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.pick_list TO app_rw;
GRANT UPDATE (status, dispatched_at) ON inventory.pick_list TO app_rw;

-- One row per lot a delivery line is picked from; a line the seller's stock cannot cover gets a
-- row with no lot for the part that is short.
CREATE TABLE inventory.pick_list_line (
    pick_line_id     uuid          PRIMARY KEY,
    pick_list_id     uuid          NOT NULL REFERENCES inventory.pick_list (pick_list_id),
    owner_entity_id  uuid          NOT NULL,
    delivery_line_id uuid          NOT NULL,
    sku_id           uuid          NOT NULL,
    location_id      uuid,
    stock_lot_id     uuid,
    batch_id         uuid,
    qty              numeric(14,3) NOT NULL CHECK (qty > 0),
    CHECK ((stock_lot_id IS NULL) = (location_id IS NULL))
);

CREATE INDEX pick_line_by_list ON inventory.pick_list_line (pick_list_id);
CREATE INDEX pick_line_by_lot ON inventory.pick_list_line (stock_lot_id) WHERE stock_lot_id IS NOT NULL;

ALTER TABLE inventory.pick_list_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.pick_list_line FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.pick_list_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.pick_list_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.pick_list_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.pick_list_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- A pick is never changed: an override (25A OverridePick, deferred) is a row of its own.
GRANT SELECT, INSERT ON inventory.pick_list_line TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 2. Opening balances
CREATE TABLE inventory.opening_balance (
    opening_balance_id uuid        PRIMARY KEY,
    owner_entity_id    uuid        NOT NULL,
    location_id        uuid        NOT NULL,
    status             text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SIGNED_ENTITY', 'POSTED')),
    prepared_by        uuid,
    prepared_at        timestamptz NOT NULL DEFAULT now(),
    signed_entity_by   uuid,
    signed_entity_at   timestamptz,
    countersigned_by   uuid,
    countersigned_at   timestamptz,
    -- The OPB document, issued when the balance is posted.
    document_id        uuid
);

-- One balance in preparation per location; a posted one is followed by movements, which the
-- handler's guard refuses a second balance on (doc 25 flow 6.9).
CREATE UNIQUE INDEX opening_balance_open_per_location ON inventory.opening_balance (location_id)
    WHERE status <> 'POSTED';

ALTER TABLE inventory.opening_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.opening_balance FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.opening_balance FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.opening_balance FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON inventory.opening_balance FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.opening_balance FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.opening_balance FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.opening_balance TO app_rw;
GRANT UPDATE (status, signed_entity_by, signed_entity_at, countersigned_by, countersigned_at, document_id)
    ON inventory.opening_balance TO app_rw;

CREATE TABLE inventory.opening_balance_line (
    line_id            uuid          PRIMARY KEY,
    opening_balance_id uuid          NOT NULL REFERENCES inventory.opening_balance (opening_balance_id),
    owner_entity_id    uuid          NOT NULL,
    location_id        uuid          NOT NULL,
    line_no            integer       NOT NULL CHECK (line_no >= 1),
    batch_id           uuid          NOT NULL,
    sku_id             uuid          NOT NULL,
    condition          text          NOT NULL CHECK (condition IN ('GOOD', 'DAMAGED')),
    qty                numeric(14,3) NOT NULL CHECK (qty > 0),
    unit_cost          numeric(14,4) NOT NULL CHECK (unit_cost >= 0),
    UNIQUE (opening_balance_id, line_no)
);

ALTER TABLE inventory.opening_balance_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.opening_balance_line FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.opening_balance_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.opening_balance_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.opening_balance_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.opening_balance_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- The counted lines are what was signed: never changed.
GRANT SELECT, INSERT ON inventory.opening_balance_line TO app_rw;
