-- Wave 4 of the code review, M3R-08 (CR-19A-14): the work a pricing review starts from. The
-- consumers of batch.registered.v1 and control_price.entered.v1 run in the scope the dispatcher
-- gives them, the event owner's, and cannot read other societies' price lists there; so each
-- records the event here, in that owner's name, and the review job (PricingReviewJob, a
-- @ScheduledJob, CR-19A-7) reads the unchecked rows with the federation-wide view, finds the
-- lists in force above the MRP or the ceiling, raises each review in its list owner's scope,
-- and marks the row checked in this row owner's scope.
CREATE TABLE pricing.review_trigger (
    trigger_id      uuid          PRIMARY KEY,
    owner_entity_id uuid          NOT NULL,
    kind            text          NOT NULL CHECK (kind IN ('BATCH_MRP', 'GAZETTE')),
    sku_id          uuid          NOT NULL,
    -- the batch of a BATCH_MRP, the control price of a GAZETTE
    source_id       uuid          NOT NULL,
    -- the batch number, the gazette reference
    source_ref      text,
    -- the printed MRP, the ceiling; tax-inclusive
    threshold_price numeric(14,2) NOT NULL,
    -- the ceiling's unit; NULL for an MRP, which is the price of the SKU's base unit
    uom_code        varchar(10),
    received_at     timestamptz   NOT NULL DEFAULT now(),
    checked_at      timestamptz,
    reviews_raised  integer,
    UNIQUE (kind, source_id)
);

CREATE INDEX review_trigger_unchecked ON pricing.review_trigger (received_at) WHERE checked_at IS NULL;

-- Row-level security: the template of db/migration/RLS_POLICY_TEMPLATE.md (no location column).
ALTER TABLE pricing.review_trigger ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.review_trigger FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON pricing.review_trigger FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON pricing.review_trigger FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON pricing.review_trigger FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON pricing.review_trigger FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON pricing.review_trigger FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- A row is checked once and nothing else changes; it is never deleted.
GRANT SELECT, INSERT ON pricing.review_trigger TO app_rw;
GRANT UPDATE (checked_at, reviews_raised) ON pricing.review_trigger TO app_rw;
