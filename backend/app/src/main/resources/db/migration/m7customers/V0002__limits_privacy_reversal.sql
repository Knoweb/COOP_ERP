-- M7-05, M7-08, M7-09, M7-10 (29 September 2026): the account's limits and states with their
-- history, adjustments, the reversal of a repayment, repayments taken at a till, and the
-- data-subject requests (27A sections 3, 6, 6.4). What differs from 27A and why is in the module
-- README ("Deviations"):
--   * the reversal of an allocation is a row of its own (allocation_reversal), not
--     allocation.reversed_at: the allocations stay insert-only, and what is live of a charge is its
--     allocations less the reversed ones;
--   * an adjustment is a row of account_adjustment (its own "document", cited by the ADJUSTMENT
--     posting), not a kernel document: no document type exists for it yet;
--   * data_subject_request knows CORRECTION beside ACCESS and ERASURE (FR-CUS-060).

-- ---------------------------------------------------------------------------------------------
-- 1. The limits and the states of an account change (AmendAccountLimits, Suspend, Reinstate,
--    Close); every change leaves a row in the account's history.
GRANT UPDATE (credit_limit, hard_block, offline_cap, status) ON customers.customer_account TO app_rw;

CREATE TABLE customers.account_history (
    history_id      uuid        PRIMARY KEY,
    account_id      uuid        NOT NULL REFERENCES customers.customer_account,
    action          text        NOT NULL CHECK (action IN ('LIMITS_AMENDED', 'SUSPENDED', 'REINSTATED', 'CLOSED')),
    before_value    jsonb       NOT NULL DEFAULT '{}',
    after_value     jsonb       NOT NULL DEFAULT '{}',
    reason          text        NOT NULL CHECK (btrim(reason) <> ''),
    changed_by      uuid,
    changed_at      timestamptz NOT NULL DEFAULT now(),
    owner_entity_id uuid        NOT NULL
);
CREATE INDEX history_of_account ON customers.account_history (account_id, changed_at);

-- 2. Adjustments (27A section 6: PostAdjustment / ApproveAdjustment, SoD request <> approve).
CREATE TABLE customers.account_adjustment (
    adjustment_id   uuid          PRIMARY KEY,
    account_id      uuid          NOT NULL REFERENCES customers.customer_account,
    amount          numeric(14,2) NOT NULL CHECK (amount <> 0),
    reason          text          NOT NULL CHECK (btrim(reason) <> ''),
    status          text          NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'APPROVED')),
    requested_by    uuid          NOT NULL,
    requested_at    timestamptz   NOT NULL DEFAULT now(),
    approved_by     uuid,
    approved_at     timestamptz,
    posting_id      uuid,
    owner_entity_id uuid          NOT NULL
);
CREATE INDEX adjustment_of_account ON customers.account_adjustment (account_id);

-- 3. The reversal of a repayment: the allocations it made are undone by a row each.
CREATE TABLE customers.allocation_reversal (
    allocation_id       uuid        PRIMARY KEY REFERENCES customers.allocation,
    reversal_posting_id uuid        NOT NULL,
    reversed_at         timestamptz NOT NULL DEFAULT now(),
    owner_entity_id     uuid        NOT NULL
);

-- 4. The CPR: a till's repayment keeps its own number and where it was taken (27A section 7.3);
--    a reversal names the CPR it reverses, and a CPR is reversed at most once.
ALTER TABLE customers.doc_customer_payment
    ADD COLUMN origin               text   NOT NULL DEFAULT 'OFFICE' CHECK (origin IN ('OFFICE', 'TILL')),
    ADD COLUMN doc_number_display   text,
    ADD COLUMN series_id            uuid,
    ADD COLUMN doc_number           bigint,
    ADD COLUMN taken_at_location_id uuid,
    ADD COLUMN device_id            uuid,
    ADD COLUMN flags                text[] NOT NULL DEFAULT '{}';
CREATE UNIQUE INDEX one_reversal_per_payment ON customers.doc_customer_payment (reversal_of)
    WHERE reversal_of IS NOT NULL;

-- 5. Data-subject requests (doc 27 section 3.2, 27A section 3): received by the society that
--    registered the customer, fulfilled or refused by its responsible officer.
CREATE TABLE customers.data_subject_request (
    request_id      uuid        PRIMARY KEY,
    customer_id     uuid        NOT NULL REFERENCES customers.customer,
    kind            text        NOT NULL CHECK (kind IN ('ACCESS', 'CORRECTION', 'ERASURE')),
    notes           text,
    received_at     timestamptz NOT NULL DEFAULT now(),
    received_by     uuid        NOT NULL,
    status          text        NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'FULFILLED', 'REFUSED')),
    fulfilled_at    timestamptz,
    fulfilled_by    uuid,
    outcome         text,
    export_sha256   char(64),
    refusal_ground  text,
    owner_entity_id uuid        NOT NULL
);
CREATE INDEX request_of_customer ON customers.data_subject_request (customer_id);

-- ---------------------------------------------------------------------------------------------
-- Row-level security: the template on every new table.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['account_history', 'account_adjustment', 'allocation_reversal', 'data_subject_request']
    LOOP
        EXECUTE format('ALTER TABLE customers.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE customers.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY own_read ON customers.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())', t);
        EXECUTE format('CREATE POLICY own_write ON customers.%I FOR INSERT TO app_rw'
                       || ' WITH CHECK (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())', t);
        EXECUTE format('CREATE POLICY fed_view ON customers.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.scope_class() = ''FEDERATION_VIEW'')', t);
        EXECUTE format('CREATE POLICY ext_view ON customers.%I FOR SELECT TO app_rw'
                       || ' USING (kernel.scope_class() = ''EXTERNAL_TIMEBOXED'''
                       || ' AND owner_entity_id = ANY (kernel.granted_entities()))', t);
        EXECUTE format('GRANT SELECT, INSERT ON customers.%I TO app_rw', t);
    END LOOP;
    -- An adjustment is approved and a request answered in place; the consents are withdrawn by
    -- an erasure.
    FOREACH t IN ARRAY ARRAY['account_adjustment', 'data_subject_request', 'customer_consent']
    LOOP
        EXECUTE format('CREATE POLICY own_update ON customers.%I FOR UPDATE TO app_rw'
                       || ' USING (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())'
                       || ' WITH CHECK (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())', t);
    END LOOP;
END;
$$;

GRANT UPDATE (status, approved_by, approved_at, posting_id) ON customers.account_adjustment TO app_rw;
GRANT UPDATE (status, fulfilled_at, fulfilled_by, outcome, export_sha256, refusal_ground)
    ON customers.data_subject_request TO app_rw;
GRANT UPDATE (withdrawn_at) ON customers.customer_consent TO app_rw;
-- The anonymiser replaces a phone number it closes (27A section 6.4: "phones removed").
GRANT UPDATE (phone, reason) ON customers.customer_phone TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- The erasure guard (27A section 6.4, RetentionGuard) asks whether the customer owes or is owed
-- anything at any society, which row-level security hides from the registering one. This
-- function answers how many accounts have a balance, nothing else; it reads through
-- balance_directory, a policy for app_seed (the migrator's group) and nobody else, as
-- customers.phone_holders does.
CREATE POLICY balance_directory ON customers.customer_account FOR SELECT TO app_seed USING (true);

CREATE FUNCTION customers.accounts_with_balance(p_customer uuid)
RETURNS integer
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, customers
AS $$
    SELECT count(*)::integer FROM customers.customer_account a WHERE a.customer_id = p_customer AND a.balance <> 0
$$;

REVOKE ALL ON FUNCTION customers.accounts_with_balance(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers.accounts_with_balance(uuid) TO app_rw;
