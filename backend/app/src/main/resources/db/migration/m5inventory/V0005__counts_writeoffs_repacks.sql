-- M5-11, M5-13 and the repack of 25A (demo scope, back office): stock counts with their
-- adjustment, write-offs with witness and approval, repack recipes and repacks.
--
--   count_task          a count at one location: FULL or by items (SKUS); SCHEDULED, COUNTING,
--                       VARIANCE_REVIEW (an adjustment waits for approval), CLOSED
--   count_expectation   the lots in scope when the count started, with what the book said then
--   count_line          what was counted, written once at submit, with the variance and its value
--   write_off           a write-off at one location (its id is the WOF document's): DRAFT,
--                       REQUESTED, WITNESSED, POSTED or REJECTED
--   write_off_line      what is written off: batch, condition, quantity
--   write_off_photo     the photographs (kernel attachments of the WOF document) it carries
--   repack_recipe       input SKU and quantity, output SKU and quantity, expected loss
--   repack              one execution: the lot consumed, the output batch produced, the yield
--   repack_reversal     the reversal of a repack, while its output is untouched
--
-- Departures from 25A's DDL, recorded in the module README: the count task keeps its expectation
-- as rows rather than jsonb and its adjustment on the task (no ADJ document yet); the loss
-- categories are the fixed list of doc 25 F-01 in the API rather than a table; a repack is a row
-- of its own (no RPK document yet) and its reversal a second row, never an update. The lines of
-- every document here are insert-only; only the task and the write-off move through their states.

-- ---------------------------------------------------------------------------------------------
-- 1. Counts
CREATE TABLE inventory.count_task (
    task_id         uuid          PRIMARY KEY,
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    scope_kind      text          NOT NULL CHECK (scope_kind IN ('FULL', 'SKUS')),
    scope_sku_ids   uuid[]        NOT NULL DEFAULT '{}',
    scheduled_for   date          NOT NULL,
    status          text          NOT NULL DEFAULT 'SCHEDULED'
                                  CHECK (status IN ('SCHEDULED', 'COUNTING', 'VARIANCE_REVIEW', 'CLOSED')),
    -- How a CLOSED count ended: POSTED (every variance within tolerance), APPROVED or REJECTED.
    outcome         text          CHECK (outcome IN ('POSTED', 'APPROVED', 'REJECTED')),
    scheduled_by    uuid,
    scheduled_at    timestamptz   NOT NULL,
    started_by      uuid,
    started_at      timestamptz,
    submitted_by    uuid,
    submitted_at    timestamptz,
    -- The value of the variances beyond tolerance (quantity x entity average), and its band.
    review_value    numeric(14,2),
    review_band     smallint,
    reviewed_by     uuid,
    reviewed_at     timestamptz,
    review_reason   text,
    CHECK ((scope_kind = 'SKUS') = (cardinality(scope_sku_ids) > 0))
);

-- One count at a time per location: two counts of the same shelf would post the same variance twice.
CREATE UNIQUE INDEX count_task_open_per_location ON inventory.count_task (location_id) WHERE status <> 'CLOSED';
CREATE INDEX count_task_by_location ON inventory.count_task (location_id, scheduled_at);

CREATE TABLE inventory.count_expectation (
    task_id         uuid          NOT NULL REFERENCES inventory.count_task (task_id),
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    batch_id        uuid          NOT NULL,
    sku_id          uuid          NOT NULL,
    condition       text          NOT NULL CHECK (condition IN ('GOOD', 'DAMAGED')),
    expected_qty    numeric(14,3) NOT NULL,
    PRIMARY KEY (task_id, batch_id, condition)
);

CREATE TABLE inventory.count_line (
    line_id          uuid          PRIMARY KEY,
    task_id          uuid          NOT NULL REFERENCES inventory.count_task (task_id),
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    line_no          integer       NOT NULL CHECK (line_no >= 1),
    batch_id         uuid          NOT NULL,
    sku_id           uuid          NOT NULL,
    condition        text          NOT NULL CHECK (condition IN ('GOOD', 'DAMAGED')),
    -- The book at submit: the expectation at start moved by everything posted since (E-06).
    expected_qty     numeric(14,3) NOT NULL,
    counted_qty      numeric(14,3) CHECK (counted_qty >= 0),
    skip_reason      text,
    variance_qty     numeric(14,3) NOT NULL DEFAULT 0,
    unit_cost        numeric(14,4) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    variance_value   numeric(14,2) NOT NULL DEFAULT 0,
    within_tolerance boolean       NOT NULL,
    UNIQUE (task_id, line_no),
    UNIQUE (task_id, batch_id, condition),
    CHECK ((counted_qty IS NULL) = (skip_reason IS NOT NULL))
);

-- ---------------------------------------------------------------------------------------------
-- 2. Write-offs
CREATE TABLE inventory.write_off (
    write_off_id     uuid          PRIMARY KEY,
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    category         text          NOT NULL CHECK (category IN ('DAMAGED_IN_TRANSIT', 'DAMAGED_IN_STORE', 'EXPIRED',
                                       'THEFT', 'SHRINKAGE_UNEXPLAINED', 'STAFF_CONSUMPTION', 'SAMPLES', 'DONATION', 'OTHER')),
    note             text,
    status           text          NOT NULL DEFAULT 'DRAFT'
                                   CHECK (status IN ('DRAFT', 'REQUESTED', 'WITNESSED', 'POSTED', 'REJECTED')),
    requested_by     uuid,
    requested_at     timestamptz   NOT NULL,
    submitted_at     timestamptz,
    document_no      text,
    value            numeric(14,2),
    band             smallint,
    witness_user_id  uuid,
    witnessed_at     timestamptz,
    remote_witness   boolean       NOT NULL DEFAULT false,
    approver_user_id uuid,
    decided_at       timestamptz,
    reject_reason    text
);

CREATE INDEX write_off_by_location ON inventory.write_off (location_id, requested_at);

CREATE TABLE inventory.write_off_line (
    line_id         uuid          PRIMARY KEY,
    write_off_id    uuid          NOT NULL REFERENCES inventory.write_off (write_off_id),
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    line_no         integer       NOT NULL CHECK (line_no >= 1),
    batch_id        uuid          NOT NULL,
    sku_id          uuid          NOT NULL,
    condition       text          NOT NULL CHECK (condition IN ('GOOD', 'DAMAGED')),
    qty             numeric(14,3) NOT NULL CHECK (qty > 0),
    UNIQUE (write_off_id, line_no)
);

CREATE TABLE inventory.write_off_photo (
    attachment_id   uuid          PRIMARY KEY,
    write_off_id    uuid          NOT NULL REFERENCES inventory.write_off (write_off_id),
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    added_by        uuid,
    added_at        timestamptz   NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- 3. Repack
CREATE TABLE inventory.repack_recipe (
    recipe_id         uuid          PRIMARY KEY,
    owner_entity_id   uuid          NOT NULL,
    name              text          NOT NULL,
    input_sku_id      uuid          NOT NULL,
    input_qty         numeric(14,3) NOT NULL CHECK (input_qty > 0),
    output_sku_id     uuid          NOT NULL,
    output_qty        numeric(14,3) NOT NULL CHECK (output_qty > 0),
    expected_loss_pct numeric(5,2)  NOT NULL DEFAULT 0 CHECK (expected_loss_pct >= 0 AND expected_loss_pct < 100),
    status            text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    defined_by        uuid,
    defined_at        timestamptz   NOT NULL,
    CHECK (input_sku_id <> output_sku_id)
);

CREATE UNIQUE INDEX repack_recipe_active_name ON inventory.repack_recipe (owner_entity_id, lower(name))
    WHERE status = 'ACTIVE';

CREATE TABLE inventory.repack (
    repack_id           uuid          PRIMARY KEY,
    owner_entity_id     uuid          NOT NULL,
    location_id         uuid          NOT NULL,
    recipe_id           uuid          NOT NULL REFERENCES inventory.repack_recipe (recipe_id),
    input_batch_id      uuid          NOT NULL,
    input_sku_id        uuid          NOT NULL,
    input_qty           numeric(14,3) NOT NULL CHECK (input_qty > 0),
    input_unit_cost     numeric(14,4) NOT NULL CHECK (input_unit_cost >= 0),
    output_sku_id       uuid          NOT NULL,
    output_batch_id     uuid          NOT NULL,
    expected_output_qty numeric(14,3) NOT NULL,
    actual_output_qty   numeric(14,3) NOT NULL CHECK (actual_output_qty > 0),
    variance_qty        numeric(14,3) NOT NULL,
    output_unit_cost    numeric(14,4) NOT NULL CHECK (output_unit_cost >= 0),
    executed_by         uuid,
    executed_at         timestamptz   NOT NULL
);

CREATE INDEX repack_by_location ON inventory.repack (location_id, executed_at);

CREATE TABLE inventory.repack_reversal (
    repack_id       uuid          PRIMARY KEY REFERENCES inventory.repack (repack_id),
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    reason          text          NOT NULL,
    reversed_by     uuid,
    reversed_at     timestamptz   NOT NULL
);

-- ---------------------------------------------------------------------------------------------
-- 4. Row-level security: the template of 17A 6.3 on every table. A shop session reads and writes
-- its own location's rows; an entity-wide user every location of the entity. The recipe has no
-- location: it is the entity's.
ALTER TABLE inventory.count_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.count_task FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON inventory.count_task FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.count_task FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON inventory.count_task FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.count_task FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.count_task FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON inventory.count_task TO app_rw;

ALTER TABLE inventory.count_expectation ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.count_expectation FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON inventory.count_expectation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.count_expectation FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.count_expectation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.count_expectation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON inventory.count_expectation TO app_rw;

ALTER TABLE inventory.count_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.count_line FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON inventory.count_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.count_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.count_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.count_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON inventory.count_line TO app_rw;

ALTER TABLE inventory.write_off ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.write_off FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON inventory.write_off FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.write_off FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON inventory.write_off FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.write_off FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.write_off FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON inventory.write_off TO app_rw;

ALTER TABLE inventory.write_off_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.write_off_line FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON inventory.write_off_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.write_off_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.write_off_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.write_off_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON inventory.write_off_line TO app_rw;

ALTER TABLE inventory.write_off_photo ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.write_off_photo FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON inventory.write_off_photo FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.write_off_photo FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.write_off_photo FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.write_off_photo FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON inventory.write_off_photo TO app_rw;

ALTER TABLE inventory.repack ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.repack FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON inventory.repack FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.repack FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.repack FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.repack FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON inventory.repack TO app_rw;

ALTER TABLE inventory.repack_reversal ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.repack_reversal FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON inventory.repack_reversal FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON inventory.repack_reversal FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON inventory.repack_reversal FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.repack_reversal FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON inventory.repack_reversal TO app_rw;


GRANT UPDATE (status, outcome, started_by, started_at, submitted_by, submitted_at, review_value, review_band,
              reviewed_by, reviewed_at, review_reason)
    ON inventory.count_task TO app_rw;
GRANT UPDATE (status, submitted_at, document_no, value, band, witness_user_id, witnessed_at, remote_witness,
              approver_user_id, decided_at, reject_reason)
    ON inventory.write_off TO app_rw;

ALTER TABLE inventory.repack_recipe ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory.repack_recipe FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON inventory.repack_recipe FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON inventory.repack_recipe FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON inventory.repack_recipe FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON inventory.repack_recipe FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON inventory.repack_recipe FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON inventory.repack_recipe TO app_rw;
GRANT UPDATE (status) ON inventory.repack_recipe TO app_rw;
