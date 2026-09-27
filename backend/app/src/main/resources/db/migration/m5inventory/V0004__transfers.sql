-- M5-09 (demo scope): an internal transfer between two locations of one entity (25A section 6.3,
-- IssueTransfer and ReceiveTransfer; doc 25 flow 6.6), a warehouse to a shop.
--
--   transfer            the issue, written at the SOURCE location: from, to, who and when
--   transfer_line       what left the source: batch, quantity, the cost it carried
--   transfer_receipt    the receipt, written at the DESTINATION location, once
--
-- No row is written by both sides (AGENTS.md idea 3; PR #148: a shop session writes at its own
-- location only). The issue writes the source's rows and posts TRANSFER_OUT there; the receipt
-- writes one row of its own at the destination and posts TRANSFER_IN there. Nothing is ever
-- updated: the transfer is IN_TRANSIT while it has no receipt row and RECEIVED once it has one.
--
-- Reading across the two sides: a session at the destination reads the transfer and its lines
-- (dest_read: they are addressed to it), and a session at the source reads the receipt
-- (source_read), so each side sees the whole transfer and writes only its own half. Both are
-- extra SELECT policies beside the template's, in the owner's OWN scope only.
--
-- Departure from 25A's DDL (recorded in the module README): 25A extends an XFR document
-- (doc_transfer keyed on document_id) with dispatched_at, received_at and received_by on one
-- row, which the destination would update; the demo keeps the two halves as two rows and cites
-- the transfer id as the movements' document. The XFR document's issuance is deferred.

-- ---------------------------------------------------------------------------------------------
-- 1. The issue, at the source
CREATE TABLE inventory.transfer (
    transfer_id     uuid        PRIMARY KEY,
    owner_entity_id uuid        NOT NULL,
    -- The source location: the row is the source's.
    location_id     uuid        NOT NULL,
    to_location_id  uuid        NOT NULL,
    issued_by       uuid,
    issued_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (to_location_id <> location_id)
);

CREATE INDEX transfer_by_source ON inventory.transfer (location_id, issued_at);
CREATE INDEX transfer_by_destination ON inventory.transfer (to_location_id, issued_at);

ALTER TABLE inventory.transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.transfer FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY dest_read ON inventory.transfer FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND to_location_id = kernel.scope_location());
CREATE POLICY own_write ON inventory.transfer FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.transfer FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.transfer FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.transfer TO app_rw;

CREATE TABLE inventory.transfer_line (
    line_id         uuid          PRIMARY KEY,
    transfer_id     uuid          NOT NULL REFERENCES inventory.transfer (transfer_id),
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    to_location_id  uuid          NOT NULL,
    line_no         integer       NOT NULL CHECK (line_no >= 1),
    batch_id        uuid          NOT NULL,
    sku_id          uuid          NOT NULL,
    qty             numeric(14,3) NOT NULL CHECK (qty > 0),
    -- The cost the TRANSFER_OUT carried (the entity average), which the TRANSFER_IN brings in.
    unit_cost       numeric(14,4) NOT NULL CHECK (unit_cost >= 0),
    UNIQUE (transfer_id, line_no)
);

ALTER TABLE inventory.transfer_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_line FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.transfer_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY dest_read ON inventory.transfer_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND to_location_id = kernel.scope_location());
CREATE POLICY own_write ON inventory.transfer_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.transfer_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.transfer_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.transfer_line TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 2. The receipt, at the destination
CREATE TABLE inventory.transfer_receipt (
    transfer_id      uuid        PRIMARY KEY REFERENCES inventory.transfer (transfer_id),
    owner_entity_id  uuid        NOT NULL,
    -- The destination location: the row is the destination's.
    location_id      uuid        NOT NULL,
    from_location_id uuid        NOT NULL,
    received_by      uuid,
    received_at      timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE inventory.transfer_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.transfer_receipt FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.transfer_receipt FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY source_read ON inventory.transfer_receipt FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND from_location_id = kernel.scope_location());
CREATE POLICY own_write ON inventory.transfer_receipt FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.transfer_receipt FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.transfer_receipt FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.transfer_receipt TO app_rw;
