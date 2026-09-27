-- M5-01: the inventory ledger (25A section 3; doc 25 sections 3.1 to 3.3; doc 18 part B).
--
--   stock_lot           one entity's holding of one batch at one location in one condition
--   stock_movement      the insert-only ledger, partitioned monthly by received_at
--   movement_sequence   the dense counter per (location, source) that numbers the movements
--   entity_sku_cost     the moving weighted average per (entity, SKU)
--
-- The rule of the ledger (AGENTS.md, doc 18 B-I4): stock_lot.qty_on_hand and
-- entity_sku_cost.qty_on_hand are sums of stock_movement, changed in the same transaction as
-- the movement and by nothing else (LedgerService). The application may insert movements and
-- never change or remove one; it may change a lot's quantity columns and never its identity.
--
-- Two departures from the guide's DDL, both recorded in the module README:
--   * stock_lot and stock_movement carry sku_id. The guide keys them on the batch alone, and
--     every query of 25A section 7 asks by SKU (balances, availability, the entity average),
--     which would otherwise join M2's catalogue.batch from this schema. The SKU is copied from
--     the batch when the lot is created (B-I3: the lot's batch is of the line's SKU).
--   * movement_sequence is new: 25A names SequenceService ("dense per location and source")
--     and no table for it.
-- The tables of the later tickets (recipes, policies, count and expiry tasks, pick lists, the
-- document extensions) arrive with those tickets, each in its own migration.

-- ---------------------------------------------------------------------------------------------
-- 1. Lots
CREATE TABLE inventory.stock_lot (
    stock_lot_id             uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    location_id              uuid          NOT NULL,
    batch_id                 uuid          NOT NULL,
    sku_id                   uuid          NOT NULL,
    condition                text          NOT NULL DEFAULT 'GOOD' CHECK (condition IN ('GOOD', 'DAMAGED')),
    qty_on_hand              numeric(14,3) NOT NULL DEFAULT 0,
    -- The lot's acquisition cost (doc 25 section 3.3): kept for traceability and claims; the
    -- costing figure is the entity average.
    unit_cost                numeric(14,4) NOT NULL CHECK (unit_cost >= 0),
    received_at              timestamptz   NOT NULL,
    negative_since           timestamptz,
    negative_acknowledged_at timestamptz,
    last_movement_seq        bigint        NOT NULL DEFAULT 0,
    CONSTRAINT stock_lot_identity_uq UNIQUE (location_id, batch_id, condition)
);

CREATE INDEX lot_by_location_batch ON inventory.stock_lot (location_id, batch_id) WHERE qty_on_hand <> 0;
CREATE INDEX lot_by_location_sku ON inventory.stock_lot (location_id, sku_id);
CREATE INDEX lot_by_sku ON inventory.stock_lot (sku_id);

ALTER TABLE inventory.stock_lot ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_lot FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.stock_lot FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.stock_lot FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON inventory.stock_lot FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.stock_lot FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.stock_lot FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- A lot is created by its first movement and never removed; only the ledger's own columns
-- change (the quantity, the sequence, the negative flag and its acknowledgement). Its identity
-- and its acquisition cost do not: those columns are not granted.
GRANT SELECT, INSERT ON inventory.stock_lot TO app_rw;
GRANT UPDATE (qty_on_hand, last_movement_seq, negative_since, negative_acknowledged_at) ON inventory.stock_lot TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 2. The movement ledger
CREATE TABLE inventory.stock_movement (
    movement_id           uuid          NOT NULL,
    received_at           timestamptz   NOT NULL DEFAULT now(),
    -- 'central' or the id of the device that numbered the movement (doc 18 I-F4); the two
    -- sequences are kept apart so both stay dense.
    source                text          NOT NULL,
    movement_seq          bigint        NOT NULL CHECK (movement_seq > 0),
    owner_entity_id       uuid          NOT NULL,
    location_id           uuid          NOT NULL,
    batch_id              uuid          NOT NULL,
    sku_id                uuid          NOT NULL,
    condition             text          NOT NULL CHECK (condition IN ('GOOD', 'DAMAGED')),
    qty_delta             numeric(14,3) NOT NULL CHECK (qty_delta <> 0),
    movement_type         text          NOT NULL CHECK (movement_type IN
        ('RECEIPT', 'SALE', 'SALE_REVERSAL', 'TRANSFER_OUT', 'TRANSFER_IN', 'REPACK_CONSUME', 'REPACK_PRODUCE',
         'COUNT_ADJUST', 'WRITE_OFF', 'RETURN_TO_SELLER', 'OPENING_BALANCE', 'GRN_REVERSAL')),
    unit_cost_at_movement numeric(14,4) NOT NULL CHECK (unit_cost_at_movement >= 0),
    -- Every movement cites its document (B-I5). No foreign key: kernel.document is partitioned
    -- and another module's table.
    document_id           uuid          NOT NULL,
    document_line_id      uuid,
    occurred_at           timestamptz   NOT NULL,
    occurred_local        timestamp,
    operator_user_id      uuid,
    device_id             uuid,
    PRIMARY KEY (received_at, movement_id),
    UNIQUE (received_at, location_id, source, movement_seq)
) PARTITION BY RANGE (received_at);

CREATE INDEX mv_by_lot ON inventory.stock_movement (location_id, batch_id, received_at);
CREATE INDEX mv_by_sku ON inventory.stock_movement (sku_id, received_at);
CREATE INDEX mv_by_doc ON inventory.stock_movement (document_id);

ALTER TABLE inventory.stock_movement ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.stock_movement FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.stock_movement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.stock_movement FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.stock_movement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.stock_movement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- Insert-only (doc 18 P4): no UPDATE, no DELETE, ever.
GRANT SELECT, INSERT ON inventory.stock_movement TO app_rw;

-- The partitions, as m2catalogue V0003 does for catalogue.batch: a default partition catches a
-- row whose month has no partition yet; each partition has RLS forced, no privilege of its own
-- (the application reads and writes through the parent) and the parent's policies copied by
-- their text. The default partition admits the migrator (app_seed) to move its rows into a new
-- month's partition: the same row leaves one partition for another, and app_rw still cannot
-- delete anything.
CREATE TABLE inventory.stock_movement_default PARTITION OF inventory.stock_movement DEFAULT;

CREATE POLICY partition_move ON inventory.stock_movement_default FOR ALL TO app_seed
    USING (true) WITH CHECK (true);

CREATE FUNCTION inventory.secure_movement_partition(partition_name text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, inventory
AS $$
DECLARE
    policy record;
BEGIN
    EXECUTE format('ALTER TABLE inventory.%I ENABLE ROW LEVEL SECURITY', partition_name);
    EXECUTE format('ALTER TABLE inventory.%I FORCE ROW LEVEL SECURITY', partition_name);
    EXECUTE format('REVOKE ALL PRIVILEGES ON inventory.%I FROM PUBLIC, app_rw', partition_name);

    FOR policy IN
        SELECT p.polname,
               CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT' WHEN 'w' THEN 'UPDATE'
                             WHEN 'd' THEN 'DELETE' ELSE 'ALL' END            AS command,
               pg_get_expr(p.polqual, p.polrelid)                             AS using_expr,
               pg_get_expr(p.polwithcheck, p.polrelid)                        AS check_expr,
               (SELECT string_agg(quote_ident(r.rolname), ', ')
                  FROM pg_roles r WHERE r.oid = ANY (p.polroles))             AS roles
          FROM pg_policy p
         WHERE p.polrelid = 'inventory.stock_movement'::regclass
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_policy q
                        WHERE q.polrelid = ('inventory.' || partition_name)::regclass
                          AND q.polname = policy.polname) THEN
            EXECUTE format('CREATE POLICY %I ON inventory.%I FOR %s TO %s', policy.polname, partition_name,
                           policy.command, policy.roles)
                    || coalesce(' USING (' || policy.using_expr || ')', '')
                    || coalesce(' WITH CHECK (' || policy.check_expr || ')', '');
        END IF;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION inventory.secure_movement_partition(text) FROM PUBLIC;

CREATE FUNCTION inventory.ensure_movement_partitions(months_ahead integer DEFAULT 3)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, inventory
AS $$
DECLARE
    offset_month   integer;
    partition_from timestamptz;
    partition_to   timestamptz;
    partition_name text;
    created        integer := 0;
BEGIN
    IF months_ahead < 0 OR months_ahead > 24 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 24';
    END IF;

    -- One creator at a time: two instances rolling over together would both find the month
    -- missing, and the second would fail on "already exists".
    PERFORM pg_advisory_xact_lock(hashtext('inventory.stock_movement partitions'));

    FOR offset_month IN 0..months_ahead LOOP
        partition_from := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                           + make_interval(months => offset_month)) AT TIME ZONE 'UTC';
        partition_to := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                         + make_interval(months => offset_month + 1)) AT TIME ZONE 'UTC';
        partition_name := 'stock_movement_' || to_char(partition_from AT TIME ZONE 'UTC', 'YYYY_MM');

        IF NOT EXISTS (SELECT 1 FROM pg_inherits
                        WHERE inhparent = 'inventory.stock_movement'::regclass
                          AND inhrelid = to_regclass('inventory.' || partition_name)) THEN
            -- Built beside the parent, filled with the rows the default partition caught for
            -- this month, then attached (a default partition holding such rows blocks a plain
            -- CREATE ... PARTITION OF).
            EXECUTE format('CREATE TABLE IF NOT EXISTS inventory.%I (LIKE inventory.stock_movement INCLUDING DEFAULTS INCLUDING CONSTRAINTS)',
                           partition_name);
            EXECUTE format('WITH moved AS (DELETE FROM inventory.stock_movement_default WHERE received_at >= %L AND received_at < %L RETURNING *)'
                           || ' INSERT INTO inventory.%I SELECT * FROM moved',
                           partition_from, partition_to, partition_name);
            EXECUTE format('ALTER TABLE inventory.stock_movement ATTACH PARTITION inventory.%I FOR VALUES FROM (%L) TO (%L)',
                           partition_name, partition_from, partition_to);
            created := created + 1;
        END IF;

        PERFORM inventory.secure_movement_partition(partition_name);
    END LOOP;

    PERFORM inventory.secure_movement_partition('stock_movement_default');

    RETURN created;
END;
$$;

REVOKE ALL ON FUNCTION inventory.ensure_movement_partitions(integer) FROM PUBLIC;
-- The application role runs the partition job (MovementPartitionMaintainer).
GRANT EXECUTE ON FUNCTION inventory.ensure_movement_partitions(integer) TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 3. The movement sequence: dense per (location, source) (25A section 11, doc 18 I-F4)
CREATE TABLE inventory.movement_sequence (
    location_id     uuid   NOT NULL,
    source          text   NOT NULL,
    owner_entity_id uuid   NOT NULL,
    last_seq        bigint NOT NULL CHECK (last_seq > 0),
    PRIMARY KEY (location_id, source)
);

ALTER TABLE inventory.movement_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.movement_sequence FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.movement_sequence FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.movement_sequence FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON inventory.movement_sequence FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.movement_sequence FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.movement_sequence FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.movement_sequence TO app_rw;
GRANT UPDATE (last_seq) ON inventory.movement_sequence TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 4. The moving weighted average per (entity, SKU) (doc 25 section 3.3; E-03, IAS 2)
CREATE TABLE inventory.entity_sku_cost (
    owner_entity_id uuid          NOT NULL,
    sku_id          uuid          NOT NULL,
    qty_on_hand     numeric(14,3) NOT NULL DEFAULT 0,
    avg_cost        numeric(14,4) NOT NULL DEFAULT 0 CHECK (avg_cost >= 0),
    updated_at      timestamptz   NOT NULL,
    PRIMARY KEY (owner_entity_id, sku_id)
);

ALTER TABLE inventory.entity_sku_cost ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.entity_sku_cost FORCE ROW LEVEL SECURITY;

-- No location: the average is the entity's, and a shop's sale moves it like a warehouse's.
CREATE POLICY own_read ON inventory.entity_sku_cost FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON inventory.entity_sku_cost FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON inventory.entity_sku_cost FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON inventory.entity_sku_cost FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.entity_sku_cost FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.entity_sku_cost TO app_rw;
GRANT UPDATE (qty_on_hand, avg_cost, updated_at) ON inventory.entity_sku_cost TO app_rw;

-- Secures the default partition and creates this month and the three after it.
SELECT inventory.ensure_movement_partitions(3);
