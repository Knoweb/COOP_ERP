-- PGL-M3-01: Regional Registration & Centralized Loyalty (Polgolla Program 2026 - Wave 1).
-- Centralizes customer loyalty identity across permitted branches/locations while strictly
-- preserving owner entity and location scope isolation under Row Level Security.
-- Regional registration captures branch enrollment, region code, membership number, and attributes.

CREATE TABLE customers.loyalty_customer (
    loyalty_id               uuid          PRIMARY KEY,
    customer_id              uuid          NOT NULL UNIQUE REFERENCES customers.customer(customer_id),
    owner_entity_id          uuid          NOT NULL,
    registered_location_id   uuid          NOT NULL,
    region_code              varchar(32)   NOT NULL,
    regional_member_no       varchar(60),
    loyalty_tier             varchar(32)   NOT NULL DEFAULT 'STANDARD'
                             CHECK (loyalty_tier IN ('STANDARD', 'SILVER', 'GOLD', 'PLATINUM', 'MEMBER')),
    status                   varchar(20)   NOT NULL DEFAULT 'ACTIVE'
                             CHECK (status IN ('ACTIVE', 'SUSPENDED', 'INACTIVE')),
    all_locations_permitted  boolean       NOT NULL DEFAULT true,
    regional_attributes      jsonb         NOT NULL DEFAULT '{}'
                             CHECK (pg_column_size(regional_attributes) <= 2048),
    enrolled_at              timestamptz   NOT NULL DEFAULT now(),
    enrolled_by              uuid,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX loyalty_customer_owner_loc ON customers.loyalty_customer (owner_entity_id, registered_location_id);
CREATE INDEX loyalty_customer_region ON customers.loyalty_customer (owner_entity_id, region_code);
CREATE INDEX loyalty_customer_reg_no ON customers.loyalty_customer (owner_entity_id, regional_member_no)
    WHERE regional_member_no IS NOT NULL;

CREATE TABLE customers.loyalty_permitted_location (
    loyalty_id               uuid          NOT NULL REFERENCES customers.loyalty_customer(loyalty_id),
    location_id              uuid          NOT NULL,
    owner_entity_id          uuid          NOT NULL,
    permitted_at             timestamptz   NOT NULL DEFAULT now(),
    permitted_by             uuid,
    revoked_at               timestamptz,
    PRIMARY KEY (loyalty_id, location_id)
);

CREATE INDEX loyalty_permitted_loc_idx ON customers.loyalty_permitted_location (owner_entity_id, location_id);

-- Row Level Security: Member personal data is the society's (CR-18-2).
-- Access-controlled by caller entity and location.

ALTER TABLE customers.loyalty_customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers.loyalty_customer FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON customers.loyalty_customer FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL
                OR all_locations_permitted
                OR registered_location_id = kernel.scope_location()
                OR EXISTS (
                    SELECT 1 FROM customers.loyalty_permitted_location pl
                     WHERE pl.loyalty_id = loyalty_customer.loyalty_id
                       AND pl.location_id = kernel.scope_location()
                       AND pl.revoked_at IS NULL
                )));

CREATE POLICY own_write ON customers.loyalty_customer FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR registered_location_id = kernel.scope_location()));

CREATE POLICY own_update ON customers.loyalty_customer FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL
                OR all_locations_permitted
                OR registered_location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity());

ALTER TABLE customers.loyalty_permitted_location ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers.loyalty_permitted_location FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON customers.loyalty_permitted_location FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_write ON customers.loyalty_permitted_location FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_update ON customers.loyalty_permitted_location FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

GRANT SELECT, INSERT ON customers.loyalty_customer TO app_rw;
GRANT UPDATE (registered_location_id, region_code, regional_member_no, loyalty_tier, status,
              all_locations_permitted, regional_attributes, updated_at)
    ON customers.loyalty_customer TO app_rw;

GRANT SELECT, INSERT ON customers.loyalty_permitted_location TO app_rw;
GRANT UPDATE (revoked_at) ON customers.loyalty_permitted_location TO app_rw;

