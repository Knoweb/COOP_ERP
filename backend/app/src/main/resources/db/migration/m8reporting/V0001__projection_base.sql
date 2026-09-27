-- M8-01: the projection base (28A sections 3 and 6; doc 28 sections 3 and 4), with the first
-- projection, the stock position (M8-04, part), as the sample the equivalence harness runs on.
--
--   projection_state   per projection and entity: the last event applied
--   stock_position     one row per (location, batch, condition), from stock.moved.v1
--
-- Every table here is droppable: its rows derive from events only, and a rebuild replays the
-- events into an emptied table (module README, "Rebuilding a projection"). The consumers write
-- in the OWN scope of the event's owner, the scope the kernel's consumer framework gives them,
-- so the ordinary template applies (RLS_POLICY_TEMPLATE.md).
--
-- Departures from 28A section 3, recorded in the module README:
--   * projection_state is keyed by (name, owner_entity_id), not by name: a consumer writes in
--     the scope of the event's owner and row-level security admits that entity's row only.
--     The guide's last_seq, shadow_table and rebuild_started_at wait for the rebuild service
--     (a consumer is handed no source sequence, and the shadow swap needs a privileged role).
--   * stock_position keeps the lot's quantity as stock.moved.v1 reports it (lotQtyOnHand),
--     with the movement's number, instead of adding deltas: a redelivered or replayed event
--     then changes nothing, whether or not the inbox saw it first. expiry_date, received_at,
--     days_held and negative_since wait for the expiry reports.

-- ---------------------------------------------------------------------------------------------
-- 1. The state of each projection
CREATE TABLE reporting.projection_state (
    name            text        NOT NULL,
    owner_entity_id uuid        NOT NULL,
    consumer        text        NOT NULL,
    status          text        NOT NULL DEFAULT 'LIVE' CHECK (status IN ('LIVE', 'REBUILDING', 'LAGGING')),
    last_event_id   uuid        NOT NULL,
    last_event_type text        NOT NULL,
    last_event_at   timestamptz NOT NULL,
    PRIMARY KEY (name, owner_entity_id)
);

ALTER TABLE reporting.projection_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.projection_state FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.projection_state FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON reporting.projection_state FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON reporting.projection_state FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON reporting.projection_state FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.projection_state FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON reporting.projection_state TO app_rw;
GRANT UPDATE (status, last_event_id, last_event_type, last_event_at) ON reporting.projection_state TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 2. Stock position (28A section 3, stock_position; doc 28 section 3: stock.moved.v1 gives the
--    current quantity by location, batch and condition)
CREATE TABLE reporting.stock_position (
    location_id       uuid          NOT NULL,
    batch_id          uuid          NOT NULL,
    condition         text          NOT NULL,
    owner_entity_id   uuid          NOT NULL,
    -- The SKU as M5 reports it. 28A resolves the canonical SKU through M2's aliases at
    -- projection time; SKU merges (sku.merged.v1) are not built, so a SKU is its own canonical.
    canonical_sku_id  uuid          NOT NULL,
    qty_on_hand       numeric(14,3) NOT NULL,
    -- The entity average at the last movement (CR-28A-1: stock.moved.v1 carries it). A cost
    -- column: this table has no PARTY policy, so no counterparty ever reads it.
    unit_cost         numeric(14,4),
    -- The number of the last movement applied and its source (dense per location and source,
    -- 25A): an older movement of the same source never overwrites a newer one.
    last_source       text          NOT NULL,
    last_movement_seq bigint        NOT NULL,
    freshness         timestamptz   NOT NULL,
    PRIMARY KEY (location_id, batch_id, condition)
);

CREATE INDEX stock_position_by_owner_sku ON reporting.stock_position (owner_entity_id, canonical_sku_id);

ALTER TABLE reporting.stock_position ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.stock_position FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.stock_position FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.stock_position FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON reporting.stock_position FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON reporting.stock_position FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.stock_position FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- The identity of a row never changes; its figures do.
GRANT SELECT, INSERT ON reporting.stock_position TO app_rw;
GRANT UPDATE (canonical_sku_id, qty_on_hand, unit_cost, last_source, last_movement_seq, freshness)
    ON reporting.stock_position TO app_rw;
