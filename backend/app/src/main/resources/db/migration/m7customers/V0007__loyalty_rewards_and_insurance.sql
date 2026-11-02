-- PGL-M3-03: Loyalty Monthly Reward & Insurance Eligibility (Polgolla Program 2026 - Wave 4).
-- Tracks monthly qualifying spend, configurable rewards/points, and insurance-benefit eligibility
-- without automated money movement. Access-controlled by caller entity under PostgreSQL Row Level Security.

-- 1. Configurable Loyalty Reward & Insurance Policy
CREATE TABLE customers.loyalty_reward_policy (
    policy_id                uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    policy_name              varchar(100)  NOT NULL,
    monthly_spend_threshold  numeric(14,2) NOT NULL DEFAULT 20000.00
                             CHECK (monthly_spend_threshold >= 0),
    min_membership_months    integer       NOT NULL DEFAULT 3
                             CHECK (min_membership_months >= 0),
    monthly_reward_points    integer       NOT NULL DEFAULT 200
                             CHECK (monthly_reward_points >= 0),
    insurance_benefit_amount numeric(14,2) NOT NULL DEFAULT 50000.00
                             CHECK (insurance_benefit_amount >= 0),
    active                   boolean       NOT NULL DEFAULT true,
    created_at               timestamptz   NOT NULL DEFAULT now(),
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    created_by               uuid,
    CONSTRAINT uq_loyalty_policy_name UNIQUE (owner_entity_id, policy_name)
);

CREATE INDEX idx_loyalty_policy_owner ON customers.loyalty_reward_policy (owner_entity_id);

-- 2. Loyalty Customer Qualifying Spend (Append-only ledger of spend transactions)
CREATE TABLE customers.loyalty_spend (
    spend_id                 uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    loyalty_id               uuid          NOT NULL REFERENCES customers.loyalty_customer(loyalty_id),
    customer_id              uuid          NOT NULL REFERENCES customers.customer(customer_id),
    location_id              uuid          NOT NULL,
    spend_date               date          NOT NULL,
    amount                   numeric(14,2) NOT NULL CHECK (amount > 0),
    receipt_number           varchar(64),
    recorded_at              timestamptz   NOT NULL DEFAULT now(),
    recorded_by              uuid
);

CREATE INDEX idx_loyalty_spend_period ON customers.loyalty_spend (owner_entity_id, loyalty_id, spend_date);

-- 3. Monthly Evaluation & Reward Snapshot
CREATE TABLE customers.loyalty_monthly_reward (
    reward_id                uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    loyalty_id               uuid          NOT NULL REFERENCES customers.loyalty_customer(loyalty_id),
    customer_id              uuid          NOT NULL REFERENCES customers.customer(customer_id),
    period_month             varchar(7)    NOT NULL CHECK (period_month ~ '^\d{4}-\d{2}$'),
    qualifying_spend         numeric(14,2) NOT NULL CHECK (qualifying_spend >= 0),
    spend_threshold          numeric(14,2) NOT NULL,
    membership_months        integer       NOT NULL,
    min_membership_months    integer       NOT NULL,
    points_awarded           integer       NOT NULL DEFAULT 0 CHECK (points_awarded >= 0),
    insurance_eligible       boolean       NOT NULL DEFAULT false,
    reward_status            varchar(20)   NOT NULL CHECK (reward_status IN ('AWARDED', 'DISQUALIFIED')),
    disqualification_reason  varchar(60),
    evaluated_at             timestamptz   NOT NULL DEFAULT now(),
    evaluated_by             uuid,
    CONSTRAINT uq_loyalty_monthly_reward UNIQUE (owner_entity_id, loyalty_id, period_month)
);

CREATE INDEX idx_loyalty_reward_owner_period ON customers.loyalty_monthly_reward (owner_entity_id, period_month);

-- 4. Loyalty Points Ledger (Insert-only)
CREATE TABLE customers.loyalty_points_ledger (
    entry_id                 uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    loyalty_id               uuid          NOT NULL REFERENCES customers.loyalty_customer(loyalty_id),
    customer_id              uuid          NOT NULL REFERENCES customers.customer(customer_id),
    points                   integer       NOT NULL,
    reason                   varchar(50)   NOT NULL,
    period_month             varchar(7),
    reference_id             uuid,
    recorded_at              timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_loyalty_points_owner_cust ON customers.loyalty_points_ledger (owner_entity_id, loyalty_id);

-- 5. Insurance Benefit Claim (Supports death-benefit workflow without automatic cash payout)
CREATE TABLE customers.loyalty_insurance_claim (
    claim_id                 uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    loyalty_id               uuid          NOT NULL REFERENCES customers.loyalty_customer(loyalty_id),
    customer_id              uuid          NOT NULL REFERENCES customers.customer(customer_id),
    claim_type               varchar(32)   NOT NULL
                             CHECK (claim_type IN ('DEATH_BENEFIT', 'HOSPITALIZATION', 'CRITICAL_ILLNESS', 'ACCIDENT', 'OTHER')),
    claim_status             varchar(32)   NOT NULL DEFAULT 'SUBMITTED'
                             CHECK (claim_status IN ('SUBMITTED', 'ELIGIBLE_SUPPORTED', 'APPROVED', 'REJECTED', 'SETTLED_EXTERNALLY')),
    policy_benefit_amount    numeric(14,2) NOT NULL CHECK (policy_benefit_amount >= 0),
    incident_date            date          NOT NULL,
    claimant_relationship    varchar(64)   NOT NULL,
    supporting_document_ref  varchar(100)  NOT NULL,
    eligibility_verified     boolean       NOT NULL DEFAULT false,
    eligibility_snapshot     jsonb         NOT NULL DEFAULT '{}'
                             CHECK (pg_column_size(eligibility_snapshot) <= 4096),
    rejection_reason         text,
    submitted_at             timestamptz   NOT NULL DEFAULT now(),
    submitted_by             uuid,
    updated_at               timestamptz   NOT NULL DEFAULT now(),
    updated_by               uuid
);

CREATE INDEX idx_loyalty_claim_owner ON customers.loyalty_insurance_claim (owner_entity_id, loyalty_id);

-- Row Level Security
ALTER TABLE customers.loyalty_reward_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers.loyalty_reward_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON customers.loyalty_reward_policy FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON customers.loyalty_reward_policy FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON customers.loyalty_reward_policy FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

ALTER TABLE customers.loyalty_spend ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers.loyalty_spend FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON customers.loyalty_spend FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON customers.loyalty_spend FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

ALTER TABLE customers.loyalty_monthly_reward ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers.loyalty_monthly_reward FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON customers.loyalty_monthly_reward FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON customers.loyalty_monthly_reward FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

ALTER TABLE customers.loyalty_points_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers.loyalty_points_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON customers.loyalty_points_ledger FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON customers.loyalty_points_ledger FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

ALTER TABLE customers.loyalty_insurance_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers.loyalty_insurance_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON customers.loyalty_insurance_claim FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON customers.loyalty_insurance_claim FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON customers.loyalty_insurance_claim FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

-- Grants to app_rw
GRANT SELECT, INSERT ON customers.loyalty_reward_policy TO app_rw;
GRANT UPDATE (policy_name, monthly_spend_threshold, min_membership_months, monthly_reward_points, insurance_benefit_amount, active, updated_at)
    ON customers.loyalty_reward_policy TO app_rw;

GRANT SELECT, INSERT ON customers.loyalty_spend TO app_rw;
GRANT SELECT, INSERT ON customers.loyalty_monthly_reward TO app_rw;
GRANT SELECT, INSERT ON customers.loyalty_points_ledger TO app_rw;

GRANT SELECT, INSERT ON customers.loyalty_insurance_claim TO app_rw;
GRANT UPDATE (claim_status, eligibility_verified, eligibility_snapshot, rejection_reason, updated_at, updated_by)
    ON customers.loyalty_insurance_claim TO app_rw;
