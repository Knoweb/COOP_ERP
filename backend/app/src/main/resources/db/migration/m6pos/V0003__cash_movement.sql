-- M6: Cash movements at the till drawer (doc 26 section 3.5, 26A section 10).
-- Records cash drops to the safe and pay-outs from the drawer.
-- Insert-only, immutable, scoped by entity/location with RLS.

CREATE TABLE pos.cash_movement (
    movement_id      uuid          PRIMARY KEY,
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    till_position_id uuid,
    device_id        uuid,
    session_id       uuid          NOT NULL,
    kind             text          NOT NULL CHECK (kind IN ('DROP', 'PAYOUT', 'FLOAT', 'CLOSE_COUNT')),
    amount           numeric(14,2) NOT NULL CHECK (amount > 0),
    occurred_at      timestamptz   NOT NULL,
    operator_user_id uuid,
    reason           text          NOT NULL,
    device_seq       bigint,
    flags            text[]        NOT NULL DEFAULT '{}',
    recorded_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX cash_movement_by_session ON pos.cash_movement (session_id, occurred_at);
CREATE INDEX cash_movement_by_location ON pos.cash_movement (location_id, occurred_at);

ALTER TABLE pos.cash_movement ENABLE ROW LEVEL SECURITY;
ALTER TABLE pos.cash_movement FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON pos.cash_movement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY own_write ON pos.cash_movement FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));

CREATE POLICY fed_view ON pos.cash_movement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON pos.cash_movement FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON pos.cash_movement TO app_rw;
