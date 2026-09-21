-- M1 Party, Tenancy & Security
-- party schema foundation

CREATE TABLE party.entity (
    entity_id uuid PRIMARY KEY,
    entity_code varchar(12) NOT NULL UNIQUE,

    entity_type text NOT NULL
        CHECK (entity_type IN ('FEDERATION', 'DISTRIBUTOR', 'MPCS')),

    legal_name_en text NOT NULL COLLATE en_icu,
    legal_name_si text COLLATE si_icu,
    legal_name_ta text COLLATE ta_icu,

    registration_no varchar(40),
    vat_registration_no varchar(20),
    district varchar(40),

    financial_year_start_month smallint NOT NULL DEFAULT 1
        CHECK (financial_year_start_month BETWEEN 1 AND 12),

    default_language text NOT NULL DEFAULT 'en'
        CHECK (default_language IN ('en', 'si', 'ta')),

    costing_policy text NOT NULL DEFAULT 'WEIGHTED_AVERAGE',

    credit_exposure_warn_only boolean NOT NULL DEFAULT true,

    responsible_officer_user_id uuid,
    data_governance_signed_on date,

    status text NOT NULL DEFAULT 'ONBOARDING'
        CHECK (status IN ('ONBOARDING', 'ACTIVE', 'SUSPENDED')),

    owner_entity_id uuid
        GENERATED ALWAYS AS (entity_id) STORED
);

CREATE UNIQUE INDEX one_federation
    ON party.entity ((entity_type))
    WHERE entity_type = 'FEDERATION';


CREATE TABLE party.entity_relationship (
    relationship_id uuid PRIMARY KEY,

    seller_entity_id uuid NOT NULL,
    buyer_entity_id uuid NOT NULL,

    price_list_id uuid,

    credit_limit numeric(14,2),
    payment_terms_days smallint,

    discrepancy_window_days smallint NOT NULL DEFAULT 7,
    order_lock_hours_before_eta smallint NOT NULL DEFAULT 24,

    allocation_rule text NOT NULL DEFAULT 'FCFS',

    status text NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'ACTIVE', 'SUSPENDED')),

    effective_from date NOT NULL,
    effective_to date,

    owner_entity_id uuid
        GENERATED ALWAYS AS (seller_entity_id) STORED,

    CHECK (seller_entity_id <> buyer_entity_id),

    CHECK (
        effective_to IS NULL
        OR effective_to >= effective_from
    ),

    EXCLUDE USING gist (
        seller_entity_id WITH =,
        buyer_entity_id WITH =,
        daterange(
            effective_from,
            coalesce(effective_to, 'infinity'::date),
            '[]'
        ) WITH &&
    )
    WHERE (status = 'ACTIVE')
);


CREATE TABLE party.location (
    location_id uuid PRIMARY KEY,

    owner_entity_id uuid NOT NULL,

    location_code varchar(12) NOT NULL,

    location_type text NOT NULL
        CHECK (location_type IN ('WAREHOUSE', 'SHOP', 'OFFICE')),

    name_en text NOT NULL COLLATE en_icu,
    name_si text COLLATE si_icu,
    name_ta text COLLATE ta_icu,

    address text,
    district varchar(40),

    geo_lat numeric(9,6),
    geo_lng numeric(9,6),

    language text
        CHECK (language IN ('en', 'si', 'ta')),

    trading_hours jsonb,

    size_band text
        CHECK (size_band IN ('S', 'M', 'L')),

    connectivity_spec_met boolean NOT NULL DEFAULT false,

    primary_till_position_id uuid,

    status text NOT NULL DEFAULT 'PLANNED'
        CHECK (
            status IN (
                'PLANNED',
                'ONBOARDING',
                'ACTIVE',
                'DORMANT'
            )
        ),

    UNIQUE (owner_entity_id, location_code)
);


CREATE OR REPLACE FUNCTION party.location_owner_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner_entity_id <> OLD.owner_entity_id THEN
        RAISE EXCEPTION 'location.owner_immutable';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_location_owner
BEFORE UPDATE ON party.location
FOR EACH ROW
EXECUTE FUNCTION party.location_owner_immutable();


CREATE TABLE party.till_position (
    till_position_id uuid PRIMARY KEY,

    location_id uuid NOT NULL
        REFERENCES party.location(location_id),

    position_no smallint NOT NULL,

    status text NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'RETIRED')),

    owner_entity_id uuid NOT NULL,

    UNIQUE (location_id, position_no)
);


CREATE TABLE party.device (
    device_id uuid PRIMARY KEY,

    hardware_serial varchar(64) NOT NULL UNIQUE,

    device_kind text NOT NULL
        CHECK (
            device_kind IN (
                'POS_TERMINAL',
                'WORKSTATION',
                'DRIVER_MOBILE'
            )
        ),

    owner_entity_id uuid NOT NULL,

    current_till_position_id uuid
        REFERENCES party.till_position(till_position_id),

    enrolled_at timestamptz,
    last_seen_at timestamptz,

    app_version varchar(20),

    status text NOT NULL DEFAULT 'ENROLLED'
        CHECK (
            status IN (
                'ENROLLED',
                'ACTIVE',
                'SUSPENDED',
                'RETIRED'
            )
        )
);

CREATE UNIQUE INDEX one_device_per_position
    ON party.device (current_till_position_id)
    WHERE current_till_position_id IS NOT NULL;