-- M7-01 (29 September 2026): the customers schema, back office part (27A section 3, doc 27
-- section 3). The society's members and its credit book: the customer, the phone history, the
-- consents, the tags, the credit account, the insert-only posting ledger, the allocations of a
-- payment to the charges it settles, and the customer payment receipt (CPR, a kernel document
-- with its extension row). What differs from 27A and why is in the module README ("Deviations"):
--   * account_posting has no allocated_amount column: what is allocated of a charge is the sum
--     of its allocation rows, so the ledger is insert-only with no UPDATE grant at all.
--   * customer_account has no mpcs_entity_id: owner_entity_id is the MPCS, the one column the
--     row-level security template and the RLS matrix test know.
--   * doc_customer_payment is not partitioned (an extension row per receipt, like M4's
--     doc_payment_receipt); data_subject_request, banking_record and banked_session come with
--     their tickets (M7-09, M7-10).

-- ---------------------------------------------------------------------------------------------
-- 1. The customer: one identity, registered by an MPCS, readable by every MPCS that holds an
--    account for it (27A section 3: "identity readable by any MPCS with an account").
CREATE TABLE customers.customer (
    customer_id             uuid        PRIMARY KEY,
    display_name            text        NOT NULL COLLATE kernel.en_icu CHECK (btrim(display_name) <> ''),
    display_name_si         text        COLLATE kernel.si_icu,
    display_name_ta         text        COLLATE kernel.ta_icu,
    language                text        NOT NULL DEFAULT 'si' CHECK (language IN ('en', 'si', 'ta')),
    -- NIC: never stored in the clear. The hash finds a second registration of the same person;
    -- the last four digits let an officer confirm an identity (27A section 6.1).
    nic_hash                char(64),
    nic_last4               char(4),
    attributes              jsonb       NOT NULL DEFAULT '{}' CHECK (pg_column_size(attributes) <= 2048),
    status                  text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'ANONYMISED')),
    registered_by_entity_id uuid        NOT NULL,
    registered_at           timestamptz NOT NULL DEFAULT now(),
    owner_entity_id         uuid        NOT NULL
);
-- gin_trgm_ops belongs to pg_trgm, which the kernel baseline installed in the kernel schema.
CREATE INDEX customer_name_trgm ON customers.customer USING gin (display_name kernel.gin_trgm_ops);
CREATE INDEX customer_nic ON customers.customer (nic_hash) WHERE nic_hash IS NOT NULL;

-- 2. The phone history: one current primary phone per number across the federation.
CREATE TABLE customers.customer_phone (
    phone_id        uuid        PRIMARY KEY,
    customer_id     uuid        NOT NULL REFERENCES customers.customer,
    phone           varchar(20) NOT NULL,
    is_primary      boolean     NOT NULL DEFAULT true,
    valid_from      timestamptz NOT NULL DEFAULT now(),
    valid_to        timestamptz,
    changed_by      uuid,
    reason          text,
    owner_entity_id uuid        NOT NULL
);
CREATE UNIQUE INDEX one_current_primary_phone ON customers.customer_phone (phone) WHERE valid_to IS NULL AND is_primary;
CREATE INDEX phone_history ON customers.customer_phone (phone, valid_to);
CREATE INDEX phone_of_customer ON customers.customer_phone (customer_id);

-- 3. Consents (PDPA): what the customer agreed to, how, and when it was withdrawn.
CREATE TABLE customers.customer_consent (
    consent_id      uuid        PRIMARY KEY,
    customer_id     uuid        NOT NULL REFERENCES customers.customer,
    purpose         text        NOT NULL CHECK (purpose IN ('CREDIT_ACCOUNT', 'STATEMENTS_NOTIFICATIONS')),
    granted_at      timestamptz NOT NULL,
    granted_via     text        NOT NULL CHECK (granted_via IN ('TILL', 'WEB', 'PAPER')),
    withdrawn_at    timestamptz,
    entity_id       uuid        NOT NULL,
    owner_entity_id uuid        NOT NULL
);
CREATE INDEX consent_of_customer ON customers.customer_consent (customer_id);

-- 4. Tags, per MPCS. Nothing is deleted in this system: a removed tag keeps its row with
--    removed_at, and tagging again clears it.
CREATE TABLE customers.customer_tag (
    customer_id     uuid        NOT NULL,
    tag_code        varchar(40) NOT NULL,
    owner_entity_id uuid        NOT NULL,
    tagged_at       timestamptz NOT NULL DEFAULT now(),
    removed_at      timestamptz,
    PRIMARY KEY (customer_id, tag_code, owner_entity_id)
);

-- 5. The credit account: one per customer and MPCS. balance is a cache, recomputed by the
--    handlers as the sum of the account's postings in the transaction that inserts one.
CREATE TABLE customers.customer_account (
    account_id      uuid          PRIMARY KEY,
    customer_id     uuid          NOT NULL REFERENCES customers.customer,
    account_no      varchar(20)   NOT NULL,
    credit_limit    numeric(14,2) NOT NULL DEFAULT 0 CHECK (credit_limit >= 0),
    balance         numeric(14,2) NOT NULL DEFAULT 0,
    offline_cap     numeric(14,2) CHECK (offline_cap >= 0),
    terms_days      smallint      NOT NULL DEFAULT 30 CHECK (terms_days > 0),
    hard_block      boolean       NOT NULL DEFAULT false,
    status          text          NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'SUSPENDED', 'CLOSED')),
    opened_at       timestamptz   NOT NULL,
    opened_by       uuid          NOT NULL,
    owner_entity_id uuid          NOT NULL,            -- the MPCS that gives the credit
    UNIQUE (customer_id, owner_entity_id),
    UNIQUE (owner_entity_id, account_no)
);

-- 6. The posting ledger: insert-only, partitioned monthly by received_at (27A section 3).
--    CHARGE is positive (a sale on account); CREDIT (a refund), PAYMENT and a REVERSAL of a
--    charge are negative; the balance is the sum.
CREATE TABLE customers.account_posting (
    posting_id       uuid          NOT NULL,
    received_at      timestamptz   NOT NULL DEFAULT now(),
    account_id       uuid          NOT NULL,
    kind             text          NOT NULL CHECK (kind IN ('CHARGE', 'CREDIT', 'PAYMENT', 'ADJUSTMENT', 'REVERSAL')),
    amount           numeric(14,2) NOT NULL CHECK (amount <> 0),
    business_date    date          NOT NULL,
    -- the till receipt (CHARGE, CREDIT) or the CPR (PAYMENT); no foreign key: kernel.document
    -- is partitioned and another module's table
    document_id      uuid          NOT NULL,
    document_line_id uuid,
    tender_seq       integer,
    receipt_number   text,
    -- where the till sold (informational): an account is the society's, charged at any of its
    -- shops, so a posting is not a row of that shop and its policies have no location line
    sold_at_location_id uuid,
    operator_user_id uuid,
    offline          boolean       NOT NULL DEFAULT false,
    limit_breached   boolean       NOT NULL DEFAULT false,
    owner_entity_id  uuid          NOT NULL,
    PRIMARY KEY (received_at, posting_id)
) PARTITION BY RANGE (received_at);

CREATE INDEX posting_by_account ON customers.account_posting (account_id, business_date);
CREATE INDEX posting_by_document ON customers.account_posting (document_id);

-- 7. What a payment settled of each charge; insert-only (a reversal, when it comes, writes its
--    own rows). What is open of a charge is its amount less the sum of its allocations.
CREATE TABLE customers.allocation (
    allocation_id      uuid          PRIMARY KEY,
    payment_posting_id uuid          NOT NULL,
    charge_posting_id  uuid          NOT NULL,
    amount             numeric(14,2) NOT NULL CHECK (amount > 0),
    created_at         timestamptz   NOT NULL DEFAULT now(),
    owner_entity_id    uuid          NOT NULL
);
CREATE INDEX allocation_of_charge ON customers.allocation (charge_posting_id);
CREATE INDEX allocation_of_payment ON customers.allocation (payment_posting_id);

-- 8. The customer payment receipt (CPR): the extension of its kernel document.
CREATE TABLE customers.doc_customer_payment (
    document_id     uuid          PRIMARY KEY,
    received_at     timestamptz   NOT NULL DEFAULT now(),
    account_id      uuid          NOT NULL,
    method          text          NOT NULL CHECK (method IN ('CASH', 'TRANSFER', 'DEPOSIT')),
    reference       text,
    amount          numeric(14,2) NOT NULL CHECK (amount > 0),
    allocation_mode text          NOT NULL DEFAULT 'OLDEST_FIRST' CHECK (allocation_mode IN ('OLDEST_FIRST', 'SPECIFIC')),
    reversal_of     uuid,
    owner_entity_id uuid          NOT NULL
);
CREATE INDEX payment_of_account ON customers.doc_customer_payment (account_id);

-- ---------------------------------------------------------------------------------------------
-- Row-level security: the template (db/migration/RLS_POLICY_TEMPLATE.md) on every table. No table
-- has a location column: a customer, an account and its postings are the whole society's.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['customer', 'customer_phone', 'customer_consent', 'customer_tag', 'customer_account',
                             'allocation', 'doc_customer_payment']
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
    -- The tables whose rows change (a cache, a status, a closed phone, a removed tag).
    FOREACH t IN ARRAY ARRAY['customer', 'customer_phone', 'customer_tag', 'customer_account']
    LOOP
        EXECUTE format('CREATE POLICY own_update ON customers.%I FOR UPDATE TO app_rw'
                       || ' USING (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())'
                       || ' WITH CHECK (kernel.scope_class() = ''OWN'' AND owner_entity_id = kernel.scope_entity())', t);
    END LOOP;
END;
$$;

-- The identity of a customer is readable by every MPCS that holds an account for it (27A
-- section 3): the subquery runs under the caller's own policies on customer_account, so it finds
-- the caller's accounts only. Balances stay OWN (customer_account has no such policy).
CREATE POLICY account_holder_read ON customers.customer FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND EXISTS (SELECT 1 FROM customers.customer_account a WHERE a.customer_id = customer.customer_id));
CREATE POLICY account_holder_read ON customers.customer_phone FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND EXISTS (SELECT 1 FROM customers.customer_account a WHERE a.customer_id = customer_phone.customer_id));

GRANT UPDATE (display_name, display_name_si, display_name_ta, language, nic_hash, nic_last4, attributes, status)
    ON customers.customer TO app_rw;
GRANT UPDATE (valid_to) ON customers.customer_phone TO app_rw;
GRANT UPDATE (removed_at, tagged_at) ON customers.customer_tag TO app_rw;
GRANT UPDATE (balance) ON customers.customer_account TO app_rw;

-- The ledger, on the template; insert-only (doc 18 P4). No location line: a till at any shop of
-- the society must read the whole balance of the account it charges (the sum of all the account's
-- postings, wherever they were made), and a posting is the account's, not the shop's.
ALTER TABLE customers.account_posting ENABLE ROW LEVEL SECURITY;
ALTER TABLE customers.account_posting FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON customers.account_posting FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON customers.account_posting FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON customers.account_posting FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON customers.account_posting FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON customers.account_posting TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- The partitions of the ledger, as m5inventory V0001 does for inventory.stock_movement: a default
-- partition catches a row whose month has no partition yet; each partition has RLS forced, no
-- privilege of its own and the parent's policies copied by their text; the default partition
-- admits the migrator (app_seed) to move its rows into a new month's partition.
CREATE TABLE customers.account_posting_default PARTITION OF customers.account_posting DEFAULT;

CREATE POLICY partition_move ON customers.account_posting_default FOR ALL TO app_seed
    USING (true) WITH CHECK (true);

CREATE FUNCTION customers.secure_posting_partition(partition_name text)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, customers
AS $$
DECLARE
    policy record;
BEGIN
    EXECUTE format('ALTER TABLE customers.%I ENABLE ROW LEVEL SECURITY', partition_name);
    EXECUTE format('ALTER TABLE customers.%I FORCE ROW LEVEL SECURITY', partition_name);
    EXECUTE format('REVOKE ALL PRIVILEGES ON customers.%I FROM PUBLIC, app_rw', partition_name);

    FOR policy IN
        SELECT p.polname,
               CASE p.polcmd WHEN 'r' THEN 'SELECT' WHEN 'a' THEN 'INSERT' WHEN 'w' THEN 'UPDATE'
                             WHEN 'd' THEN 'DELETE' ELSE 'ALL' END            AS command,
               pg_get_expr(p.polqual, p.polrelid)                             AS using_expr,
               pg_get_expr(p.polwithcheck, p.polrelid)                        AS check_expr,
               (SELECT string_agg(quote_ident(r.rolname), ', ')
                  FROM pg_roles r WHERE r.oid = ANY (p.polroles))             AS roles
          FROM pg_policy p
         WHERE p.polrelid = 'customers.account_posting'::regclass
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_policy q
                        WHERE q.polrelid = ('customers.' || partition_name)::regclass
                          AND q.polname = policy.polname) THEN
            EXECUTE format('CREATE POLICY %I ON customers.%I FOR %s TO %s', policy.polname, partition_name,
                           policy.command, policy.roles)
                    || coalesce(' USING (' || policy.using_expr || ')', '')
                    || coalesce(' WITH CHECK (' || policy.check_expr || ')', '');
        END IF;
    END LOOP;
END;
$$;

REVOKE ALL ON FUNCTION customers.secure_posting_partition(text) FROM PUBLIC;

CREATE FUNCTION customers.ensure_posting_partitions(months_ahead integer DEFAULT 3)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, customers
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

    PERFORM pg_advisory_xact_lock(hashtext('customers.account_posting partitions'));

    FOR offset_month IN 0..months_ahead LOOP
        partition_from := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                           + make_interval(months => offset_month)) AT TIME ZONE 'UTC';
        partition_to := (date_trunc('month', current_timestamp AT TIME ZONE 'UTC')
                         + make_interval(months => offset_month + 1)) AT TIME ZONE 'UTC';
        partition_name := 'account_posting_' || to_char(partition_from AT TIME ZONE 'UTC', 'YYYY_MM');

        IF NOT EXISTS (SELECT 1 FROM pg_inherits
                        WHERE inhparent = 'customers.account_posting'::regclass
                          AND inhrelid = to_regclass('customers.' || partition_name)) THEN
            EXECUTE format('CREATE TABLE IF NOT EXISTS customers.%I (LIKE customers.account_posting INCLUDING DEFAULTS INCLUDING CONSTRAINTS)',
                           partition_name);
            EXECUTE format('WITH moved AS (DELETE FROM customers.account_posting_default WHERE received_at >= %L AND received_at < %L RETURNING *)'
                           || ' INSERT INTO customers.%I SELECT * FROM moved',
                           partition_from, partition_to, partition_name);
            EXECUTE format('ALTER TABLE customers.account_posting ATTACH PARTITION customers.%I FOR VALUES FROM (%L) TO (%L)',
                           partition_name, partition_from, partition_to);
            created := created + 1;
        END IF;

        PERFORM customers.secure_posting_partition(partition_name);
    END LOOP;

    PERFORM customers.secure_posting_partition('account_posting_default');

    RETURN created;
END;
$$;

REVOKE ALL ON FUNCTION customers.ensure_posting_partitions(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers.ensure_posting_partitions(integer) TO app_rw;

SELECT customers.ensure_posting_partitions(3);

-- ---------------------------------------------------------------------------------------------
-- Reuse detection (27A section 6.1) must see who holds a phone number in every MPCS, which row
-- level security hides. This function answers the holders' ids and when they let the number go,
-- nothing else (never a name): the handler turns a holder of another MPCS into a refusal. The
-- function runs as its owner, the migrator, whom FORCE subjects to the policies too: it reads
-- through phone_directory, a policy for app_seed (the migrator's group) and nobody else.
CREATE POLICY phone_directory ON customers.customer_phone FOR SELECT TO app_seed USING (true);

CREATE FUNCTION customers.phone_holders(p_phone text)
RETURNS TABLE (customer_id uuid, owner_entity_id uuid, valid_to timestamptz)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, customers
AS $$
    SELECT ph.customer_id, ph.owner_entity_id, ph.valid_to
      FROM customers.customer_phone ph
     WHERE ph.phone = p_phone AND ph.is_primary
$$;

REVOKE ALL ON FUNCTION customers.phone_holders(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION customers.phone_holders(text) TO app_rw;
