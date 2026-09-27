-- M4-01: the trading schema (24A section 3, restated from doc 24 section 3), built for the demo
-- of 27 September 2026 (docs/PROGRESS.md, "M4-01"). What differs from 24A section 3 and why,
-- in short (the module README lists every deviation):
--
--   1. The extension tables of a document carry no owner_entity_id and no policies of their
--      own class: they follow the header, as kernel.document_line does (RLS_POLICY_TEMPLATE.md,
--      "The rows of a document"): document_read on kernel.document_visible(document_id),
--      document_write and document_update on kernel.document_owned(document_id). A row is seen
--      under whichever class sees its document (the owner, the counterparty through PARTY, the
--      Federation view, an external grant) and written by the owner in an OWN scope only.
--   2. No partitions. kernel.document itself is not partitioned on main (K-07), and a partition
--      key in every primary key would put received_at into every foreign reference for a
--      volume the demo is nowhere near. Deferred for the demo (docs/PLAN_TO_M2.md).
--   3. The seller's acceptance of an order is the seller's own record, trading.order_allocation
--      and trading.order_allocation_line, not columns of the buyer's doc_order: the order is
--      the buyer's document and only its owner writes it (kernel.document_owned; AGENTS.md idea
--      3, "no record is ever written by two parties"). 24A section 3's allocated_qty,
--      fulfilled_qty, tier_price, committed_eta, lock_at and allocation_run_id live there. The
--      two tables carry owner (seller) and counterparty (buyer) and take the template with
--      party_read, so the buyer reads the seller's decision on its order (CR-24A-1).
--   4. doc_grn_line carries batch_id and unit_cost: the batch is registered after the GRN has
--      its number (the synthetic batch number is S-<GRN number>-<line>, doc 22 section 3.7) and
--      a kernel document line is frozen at issuance, so the batch is written on the extension
--      row, in the same transaction (CR-24A-1).
--   5. The tables of the tickets deferred after the demo (claim, payment receipt, cheque,
--      exposure, transfer request, the PARTY masking views) arrive with those tickets, each in a
--      new migration of this lane.
--
-- Money numeric(14,2), unit prices and costs numeric(14,4), quantities numeric(14,3); UUIDv7
-- keys; timestamptz (AGENTS.md). No foreign key crosses into kernel or party (rule R4): a
-- document_id names a row of kernel.document, a relationship_id one of party.entity_relationship.

-- ---------------------------------------------------------------------------------------------
-- 1. Order (doc 24 section 3.1): the buyer's request. The seller's answer is section 2.
CREATE TABLE trading.doc_order (
    document_id      uuid        PRIMARY KEY,
    relationship_id  uuid        NOT NULL,
    buyer_entity_id  uuid        NOT NULL,
    seller_entity_id uuid        NOT NULL,
    requested_eta    date,
    version          smallint    NOT NULL DEFAULT 1,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- One row per kernel document line: what the buyer asked for and what it cancelled of it.
CREATE TABLE trading.doc_order_line (
    line_id       uuid          PRIMARY KEY,
    document_id   uuid          NOT NULL,
    requested_qty numeric(14,3) NOT NULL CHECK (requested_qty > 0),
    cancelled_qty numeric(14,3) NOT NULL DEFAULT 0 CHECK (cancelled_qty >= 0),
    CHECK (cancelled_qty <= requested_qty)
);
CREATE INDEX doc_order_line_document ON trading.doc_order_line (document_id);

-- ---------------------------------------------------------------------------------------------
-- 2. Allocation (doc 24 section 3.1): the seller's decision on a submitted order, one per order.
--    The seller owns it; the buyer is its counterparty and reads it (party_read).
CREATE TABLE trading.allocation_run (
    run_id                uuid        PRIMARY KEY,
    owner_entity_id       uuid        NOT NULL,
    ran_at                timestamptz NOT NULL DEFAULT now(),
    rule                  text        NOT NULL,
    availability_snapshot jsonb       NOT NULL,
    order_ids             uuid[]      NOT NULL,
    overrides             jsonb       NOT NULL DEFAULT '[]',
    ran_by                uuid
);

CREATE TABLE trading.order_allocation (
    order_id               uuid        PRIMARY KEY,
    run_id                 uuid,
    owner_entity_id        uuid        NOT NULL,
    counterparty_entity_id uuid        NOT NULL,
    relationship_id        uuid        NOT NULL,
    -- The default is what the handler always sets; it lets the RLS matrix's made-up row pass the CHECK.
    status                 text        NOT NULL DEFAULT 'ACCEPTED' CHECK (status IN ('ACCEPTED', 'REJECTED')),
    committed_eta          date,
    lock_at                timestamptz,
    lock_override_by       uuid,
    reason_code            text,
    reason_text            text,
    decided_by             uuid,
    decided_at             timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trading.order_allocation_line (
    order_line_id          uuid          PRIMARY KEY,
    order_id               uuid          NOT NULL,
    owner_entity_id        uuid          NOT NULL,
    counterparty_entity_id uuid          NOT NULL,
    allocated_qty          numeric(14,3) NOT NULL CHECK (allocated_qty >= 0),
    fulfilled_qty          numeric(14,3) NOT NULL DEFAULT 0 CHECK (fulfilled_qty >= 0),
    tier_price             numeric(14,4),
    override_reason        text,
    CHECK (fulfilled_qty <= allocated_qty)
);
CREATE INDEX order_allocation_line_order ON trading.order_allocation_line (order_id);

-- ---------------------------------------------------------------------------------------------
-- 3. Delivery note (doc 24 section 3.2): the seller's document, several drops, lines per drop.
CREATE TABLE trading.doc_delivery (
    document_id      uuid        PRIMARY KEY,
    seller_entity_id uuid        NOT NULL,
    buyer_entity_id  uuid        NOT NULL,
    vehicle_ref      text,
    driver_user_id   uuid,
    driver_name      text,
    dispatched_at    timestamptz,
    route_ref        text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- A drop's RECEIVED state is not written here: the receiver's confirmed GRN names the drop, and
-- the delivery queries read it back (the receiver never writes the seller's rows; CR-24A-1).
CREATE TABLE trading.doc_delivery_drop (
    drop_id             uuid     PRIMARY KEY,
    document_id         uuid     NOT NULL,
    seq                 smallint NOT NULL CHECK (seq >= 1),
    ship_to_location_id uuid     NOT NULL,
    bill_to_entity_id   uuid     NOT NULL,
    order_ids           uuid[]   NOT NULL,
    status              text     NOT NULL DEFAULT 'PLANNED'
        CHECK (status IN ('PLANNED', 'DELIVERED', 'UNDELIVERED')),
    arrived_at          timestamptz,
    pod_kind            text     CHECK (pod_kind IN ('PAPER', 'APP')),
    pod_signature_key   text,
    pod_photo_key       text,
    delivered_by        uuid,
    undelivered_reason  text,
    UNIQUE (document_id, seq)
);

CREATE TABLE trading.doc_delivery_line (
    line_id        uuid          PRIMARY KEY,
    document_id    uuid          NOT NULL,
    drop_id        uuid          NOT NULL,
    order_id       uuid          NOT NULL,
    order_line_id  uuid          NOT NULL,
    dispatched_qty numeric(14,3) NOT NULL CHECK (dispatched_qty > 0)
);
CREATE INDEX doc_delivery_line_document ON trading.doc_delivery_line (document_id);
CREATE INDEX doc_delivery_line_drop ON trading.doc_delivery_line (drop_id);

-- ---------------------------------------------------------------------------------------------
-- 4. Goods received note (doc 24 section 3.3): the receiver's document, the pivot.
CREATE TABLE trading.doc_grn (
    document_id          uuid        PRIMARY KEY,
    receiver_entity_id   uuid        NOT NULL,
    receiver_location_id uuid        NOT NULL,
    seller_entity_id     uuid,
    relationship_id      uuid,
    drop_id              uuid,
    delivery_document_id uuid,
    purchase_order_ref   text,
    supplier_id          uuid,
    received_on          date        NOT NULL,
    counted_by           uuid,
    confirmed_by         uuid,
    confirmed_at         timestamptz,
    reversal_of          uuid,
    created_at           timestamptz NOT NULL DEFAULT now(),
    -- A GRN is against a drop of a delivery note, or a local supply from a supplier: never both, never neither.
    CHECK ((drop_id IS NULL) <> (supplier_id IS NULL))
);
CREATE INDEX doc_grn_drop ON trading.doc_grn (drop_id) WHERE drop_id IS NOT NULL;

CREATE TABLE trading.doc_grn_line (
    line_id          uuid          PRIMARY KEY,
    document_id      uuid          NOT NULL,
    expected_qty     numeric(14,3),
    received_qty     numeric(14,3) NOT NULL CHECK (received_qty >= 0),
    damaged_qty      numeric(14,3) NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    batch_no         varchar(40),
    manufacture_date date,
    expiry_date      date,
    printed_mrp      numeric(14,2),
    unit_cost        numeric(14,4),
    batch_id         uuid,
    CHECK (damaged_qty <= received_qty)
);
CREATE INDEX doc_grn_line_document ON trading.doc_grn_line (document_id);

-- ---------------------------------------------------------------------------------------------
-- 5. Discrepancy (doc 24 section 3.4): raised by the GRN confirmation, the receiver's document,
--    at the GRN's location (a shop session writes only at its own location).
CREATE TABLE trading.doc_discrepancy (
    document_id          uuid        PRIMARY KEY,
    grn_document_id      uuid        NOT NULL,
    delivery_document_id uuid,
    kind                 text        NOT NULL CHECK (kind IN ('SHORT', 'OVER', 'DAMAGED', 'MIXED')),
    window_ends_at       timestamptz NOT NULL,
    proposal             jsonb,
    proposed_by          uuid,
    resolution           text,
    resolved_by          uuid,
    resolved_at          timestamptz,
    escalated_at         timestamptz,
    arbiter_user_id      uuid,
    arbitration          jsonb
);

CREATE TABLE trading.doc_discrepancy_line (
    line_id      uuid          PRIMARY KEY,
    document_id  uuid          NOT NULL,
    grn_line_id  uuid          NOT NULL,
    expected_qty numeric(14,3),
    received_qty numeric(14,3) NOT NULL,
    damaged_qty  numeric(14,3) NOT NULL DEFAULT 0,
    variance_qty numeric(14,3) NOT NULL
);
CREATE INDEX doc_discrepancy_line_document ON trading.doc_discrepancy_line (document_id);

-- ---------------------------------------------------------------------------------------------
-- 6. Invoice (doc 24 section 3.6): the seller's fiscal document. settled_amount and
--    credited_amount are caches recomputed from document_link by the handlers that write the
--    links (doc 24 section 9.4); nobody writes them before M4-09.
CREATE TABLE trading.doc_invoice (
    document_id          uuid          PRIMARY KEY,
    relationship_id      uuid          NOT NULL,
    seller_entity_id     uuid          NOT NULL,
    buyer_entity_id      uuid          NOT NULL,
    grn_document_ids     uuid[]        NOT NULL,
    delivery_document_id uuid,
    seller_vat_no        varchar(20)   NOT NULL,
    buyer_vat_no         varchar(20)   NOT NULL,
    tax_point_date       date          NOT NULL,
    due_date             date          NOT NULL,
    settled_amount       numeric(14,2) NOT NULL DEFAULT 0,
    credited_amount      numeric(14,2) NOT NULL DEFAULT 0,
    e_invoice_status     text          NOT NULL DEFAULT 'NOT_SUBMITTED',
    dispute_reason       text
);
CREATE INDEX doc_invoice_grns ON trading.doc_invoice USING gin (grn_document_ids);

-- ---------------------------------------------------------------------------------------------
-- 7. Posting map (doc 24 section 3.9): account roles per document type and line kind, seeded
--    from seed/m4trading/posting-map.yaml by M4SeedLoader as the migrator; mapped to a chart of
--    accounts when the accounting system is chosen (J-02).
CREATE TABLE trading.posting_map (
    doc_type_code varchar(8) NOT NULL,
    line_kind     text       NOT NULL,
    side          text       NOT NULL CHECK (side IN ('SELLER', 'BUYER')),
    debit_role    text       NOT NULL,
    credit_role   text       NOT NULL,
    amount_source text       NOT NULL,
    PRIMARY KEY (doc_type_code, line_kind, side, debit_role, credit_role)
);

-- ---------------------------------------------------------------------------------------------
-- Row-level security.
--
-- (a) The rows of a document follow the header (RLS_POLICY_TEMPLATE.md, "The rows of a
--     document"): visible under whichever class sees the document, written and updated by its
--     owner in an OWN scope. kernel.document_owned tests the class itself.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'doc_order', 'doc_order_line',
        'doc_delivery', 'doc_delivery_drop', 'doc_delivery_line',
        'doc_grn', 'doc_grn_line',
        'doc_discrepancy', 'doc_discrepancy_line',
        'doc_invoice']
    LOOP
        EXECUTE format('ALTER TABLE trading.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE trading.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY document_read ON trading.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.document_visible(document_id))', t);
        EXECUTE format('CREATE POLICY document_write ON trading.%I FOR INSERT TO app_rw'
                       || ' WITH CHECK (kernel.document_owned(document_id))', t);
        EXECUTE format('GRANT SELECT, INSERT ON trading.%I TO app_rw', t);
    END LOOP;
END;
$$;

-- The owner's updates, by column, where a handler specification changes one (24A section 6):
-- the buyer's partial cancel; the receiver's confirmation and its batches; the drop's arrival
-- and proof of delivery (M4-04's driver API is deferred; the grant is here so the migration is
-- not reopened).
CREATE POLICY document_update ON trading.doc_order_line FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT UPDATE (cancelled_qty) ON trading.doc_order_line TO app_rw;

CREATE POLICY document_update ON trading.doc_delivery FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT UPDATE (vehicle_ref, driver_user_id, driver_name, dispatched_at, route_ref) ON trading.doc_delivery TO app_rw;

CREATE POLICY document_update ON trading.doc_delivery_drop FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT UPDATE (status, arrived_at, pod_kind, pod_signature_key, pod_photo_key, delivered_by, undelivered_reason)
    ON trading.doc_delivery_drop TO app_rw;

CREATE POLICY document_update ON trading.doc_grn FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT UPDATE (confirmed_by, confirmed_at) ON trading.doc_grn TO app_rw;

CREATE POLICY document_update ON trading.doc_grn_line FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id)) WITH CHECK (kernel.document_owned(document_id));
GRANT UPDATE (batch_id, unit_cost) ON trading.doc_grn_line TO app_rw;

-- (b) The seller's own tables, on the template (db/migration/RLS_POLICY_TEMPLATE.md): no
--     location column, so no location line; the two allocation tables have a counterparty (the
--     buyer) and party_read.
ALTER TABLE trading.allocation_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.allocation_run FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.allocation_run FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON trading.allocation_run FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON trading.allocation_run FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.allocation_run FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.allocation_run TO app_rw;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['order_allocation', 'order_allocation_line']
    LOOP
        EXECUTE format('ALTER TABLE trading.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE trading.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY own_read ON trading.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())', t);
        EXECUTE format('CREATE POLICY own_write ON trading.%I FOR INSERT TO app_rw'
                       || ' WITH CHECK (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())', t);
        EXECUTE format('CREATE POLICY party_read ON trading.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.scope_class() IN (''OWN'', ''PARTY'')'
                       || ' AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()))', t);
        EXECUTE format('CREATE POLICY fed_view ON trading.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.scope_class() = ''FEDERATION_VIEW'')', t);
        EXECUTE format('CREATE POLICY ext_view ON trading.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.scope_class() = ''EXTERNAL_TIMEBOXED'''
                       || ' AND owner_entity_id = ANY (kernel.granted_entities()))', t);
        EXECUTE format('GRANT SELECT, INSERT ON trading.%I TO app_rw', t);
    END LOOP;
END;
$$;

-- The seller's fulfilment of its own allocation, at delivery note issue (24A section 6,
-- IssueDeliveryNote: "order lines fulfilled_qty += dispatched").
CREATE POLICY own_update ON trading.order_allocation_line FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
GRANT UPDATE (fulfilled_qty) ON trading.order_allocation_line TO app_rw;

-- (c) The posting map is reference data: every class but NONE reads it, the migrator seeds it
--     (RLS_POLICY_TEMPLATE.md, "Reference data and projections").
ALTER TABLE trading.posting_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.posting_map FORCE ROW LEVEL SECURITY;
CREATE POLICY reference_read ON trading.posting_map FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');
CREATE POLICY seed_reference ON trading.posting_map TO app_seed
    USING (true) WITH CHECK (true);
GRANT SELECT ON trading.posting_map TO app_rw;
GRANT SELECT, INSERT, UPDATE ON trading.posting_map TO app_seed;
