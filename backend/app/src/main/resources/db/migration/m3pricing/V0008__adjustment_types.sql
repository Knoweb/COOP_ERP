-- M3-07 / PRC-10: adjustment types (23A section 3.1; doc 23 section 3.2; doc 18 section 4.8).
-- Registered types of manual adjustments (SPECIAL_DISCOUNT, DELIVERY_FEE) that can be recorded
-- on Federation->distributor orders.

CREATE TABLE pricing.adjustment_type (
    code            varchar(24) PRIMARY KEY,
    name_en         text        NOT NULL,
    kind            text        NOT NULL CHECK (kind IN ('SPECIAL_DISCOUNT', 'DELIVERY_FEE')),
    permission_code varchar(64) NOT NULL,
    max_percent     numeric(5,2),
    max_amount      numeric(14,2),
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    owner_entity_id uuid        NOT NULL
);

-- Seed standard adjustment types owned by the Federation (kernel.system_entity()).
INSERT INTO pricing.adjustment_type (code, name_en, kind, permission_code, max_percent, max_amount, status, owner_entity_id)
VALUES
    ('SPECIAL_DISC', 'Special Discount', 'SPECIAL_DISCOUNT', 'prc.adjust.special', 5.00, NULL, 'ACTIVE', coalesce(kernel.system_entity(), '0190e000-0000-7000-8000-00000000f0f0'::uuid)),
    ('DELIVERY_FEE', 'Delivery Fee', 'DELIVERY_FEE', 'prc.adjust.delivery', NULL, 25000.00, 'ACTIVE', coalesce(kernel.system_entity(), '0190e000-0000-7000-8000-00000000f0f0'::uuid))
ON CONFLICT (code) DO NOTHING;

ALTER TABLE pricing.adjustment_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.adjustment_type FORCE ROW LEVEL SECURITY;

CREATE POLICY everyone_reads ON pricing.adjustment_type FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE');
CREATE POLICY own_write ON pricing.adjustment_type FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON pricing.adjustment_type FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

GRANT SELECT, INSERT ON pricing.adjustment_type TO app_rw;
GRANT UPDATE (status, max_percent, max_amount) ON pricing.adjustment_type TO app_rw;
