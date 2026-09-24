-- K-07: the document base and gapless numbering (doc 18 part C; 19A section 7; 24B). Lane C.
--
-- Seven tables. document_type is reference data (the registry of kinds, seeded from
-- seed/kernel/document-types.yaml). numbering_series is one counter per series with one
-- writer: the number is taken by a single-row UPDATE ... RETURNING inside the issuance
-- transaction, so a crash leaves both the number and the document or neither (C-I2, 5.5).
-- document is the shared header, immutable once issued_at is set except for status (C-I1,
-- enforced by a trigger and by the grants); document_line, document_link and
-- document_state_history are insert-only; document_attachment waits for K-09's presign.
--
-- Not partitioned, on purpose, and said out loud: 19A section 7 partitions "the volume tables"
-- by month on received_at. A partitioned header cannot be the target of a plain foreign key
-- on document_id, and doc 18 keys every module extension table one-to-one on document_id
-- (I-F10) and every link on it too. Partitioning the header would put received_at into every
-- one of those keys. received_at is kept on every table so that the partitioning can be added
-- when the volume asks for it, by a migration that rebuilds the tables; the decision is
-- recorded in docs/PROGRESS.md for the architect (doc 14 DR-6 tiering).

-- ---- document_type: reference data ------------------------------------------------------------

CREATE TABLE kernel.document_type (
    doc_type_code varchar(8) PRIMARY KEY,
    name_en text NOT NULL,
    name_si text,
    name_ta text,
    series_scope text NOT NULL
        CHECK (series_scope IN ('ENTITY', 'LOCATION', 'TILL_POSITION')),
    issuer_role text NOT NULL
        CHECK (issuer_role IN ('BUYER', 'SELLER', 'HOLDER')),
    bilateral boolean NOT NULL DEFAULT false,
    fiscal boolean NOT NULL DEFAULT false,
    offline_issuable boolean NOT NULL DEFAULT false,
    posting_map jsonb,
    owning_module text NOT NULL
);

ALTER TABLE kernel.document_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.document_type FORCE ROW LEVEL SECURITY;

-- Reference data belongs to no tenant: every class reads it, only the seed writes it.
CREATE POLICY reference_read ON kernel.document_type
    FOR SELECT TO app_rw USING (true);
CREATE POLICY seed_reference ON kernel.document_type
    FOR ALL TO app_seed USING (true) WITH CHECK (true);

REVOKE ALL PRIVILEGES ON kernel.document_type FROM PUBLIC;
GRANT SELECT ON kernel.document_type TO app_rw;
GRANT SELECT, INSERT, UPDATE ON kernel.document_type TO app_seed;

-- ---- numbering_series: one counter per series, one writer ----------------------------------

CREATE TABLE kernel.numbering_series (
    series_id uuid PRIMARY KEY,
    doc_type_code varchar(8) NOT NULL REFERENCES kernel.document_type (doc_type_code),
    series_scope text NOT NULL
        CHECK (series_scope IN ('ENTITY', 'LOCATION', 'TILL_POSITION')),
    owner_entity_id uuid NOT NULL,
    location_id uuid,
    till_position_id uuid,
    -- {entity_code}[-{location_code}[-T{position}]]-{type}; the display is {prefix}-{number:07d} (24B).
    prefix varchar(24) NOT NULL,
    next_number bigint NOT NULL DEFAULT 1
        CHECK (next_number >= 1),
    -- LOCATION scope: the shop's primary till; TILL_POSITION scope: the assigned device; null for ENTITY.
    holder_device_id uuid,
    fy_reset boolean NOT NULL DEFAULT false
        CHECK (fy_reset = false),
    status text NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CLOSED')),
    registered_at timestamptz NOT NULL DEFAULT now(),
    received_at timestamptz NOT NULL DEFAULT now(),
    CHECK (
        (series_scope = 'ENTITY' AND location_id IS NULL AND till_position_id IS NULL)
        OR (series_scope = 'LOCATION' AND location_id IS NOT NULL AND till_position_id IS NULL)
        OR (series_scope = 'TILL_POSITION' AND location_id IS NOT NULL AND till_position_id IS NOT NULL)
    ),
    -- One series per type and scope; NULLS NOT DISTINCT so two ENTITY series cannot coexist.
    UNIQUE NULLS NOT DISTINCT (doc_type_code, owner_entity_id, location_id, till_position_id)
);

CREATE INDEX numbering_series_holder_idx
    ON kernel.numbering_series (owner_entity_id, location_id, till_position_id);

ALTER TABLE kernel.numbering_series ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.numbering_series FORCE ROW LEVEL SECURITY;

-- The template (RLS_POLICY_TEMPLATE.md) plus own_update: the counter is the one row an
-- operational table updates in place, by the entity that owns it, in its own scope.
CREATE POLICY own_read ON kernel.numbering_series FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON kernel.numbering_series FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON kernel.numbering_series FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.numbering_series FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.numbering_series FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.numbering_series FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.numbering_series TO app_rw;
GRANT UPDATE (next_number, holder_device_id, status) ON kernel.numbering_series TO app_rw;

-- ---- document: the shared header --------------------------------------------------------------

CREATE TABLE kernel.document (
    document_id uuid PRIMARY KEY,
    doc_type_code varchar(8) NOT NULL REFERENCES kernel.document_type (doc_type_code),
    series_id uuid REFERENCES kernel.numbering_series (series_id),
    doc_number bigint,
    doc_number_display varchar(40),
    owner_entity_id uuid NOT NULL,
    counterparty_entity_id uuid,
    location_id uuid,
    till_position_id uuid,
    device_id uuid,
    status text NOT NULL,
    issued_at timestamptz,
    issued_local timestamp,
    business_date date,
    operator_user_id uuid,
    currency char(3) NOT NULL DEFAULT 'LKR',
    net_amount numeric(14,2),
    tax_amount numeric(14,2),
    gross_amount numeric(14,2),
    reference_document_id uuid REFERENCES kernel.document (document_id),
    content_hash char(64),
    origin text NOT NULL DEFAULT 'ONLINE'
        CHECK (origin IN ('ONLINE', 'OFFLINE')),
    device_seq bigint,
    engine_version text,
    snapshot_version bigint,
    notes text,
    received_at timestamptz NOT NULL DEFAULT now(),
    -- Number and issuance go together: neither without the other (5.5).
    CHECK ((issued_at IS NULL) = (doc_number IS NULL)),
    CHECK (issued_at IS NULL
           OR (series_id IS NOT NULL AND doc_number_display IS NOT NULL
               AND content_hash IS NOT NULL AND business_date IS NOT NULL)),
    -- C-I2: unique within the series; density is checked by kernel.numbering_gaps().
    UNIQUE (series_id, doc_number)
);

CREATE INDEX document_owner_type_issued_idx
    ON kernel.document (owner_entity_id, doc_type_code, issued_at DESC);
CREATE INDEX document_counterparty_idx
    ON kernel.document (counterparty_entity_id)
    WHERE counterparty_entity_id IS NOT NULL;
CREATE INDEX document_location_idx
    ON kernel.document (location_id, business_date)
    WHERE location_id IS NOT NULL;

-- C-I1: after issued_at is set, nothing but status changes. Raised as a check violation so
-- that the application maps it to the problem document.immutable.
CREATE OR REPLACE FUNCTION kernel.document_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.issued_at IS NOT NULL
       AND (to_jsonb(NEW) - 'status') IS DISTINCT FROM (to_jsonb(OLD) - 'status') THEN
        RAISE EXCEPTION 'document.immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_document_immutable
    BEFORE UPDATE ON kernel.document
    FOR EACH ROW EXECUTE FUNCTION kernel.document_immutable();

-- Lines, links and history never change at all; the grants say so for the application and
-- this trigger says so for everybody else.
CREATE OR REPLACE FUNCTION kernel.document_rows_insert_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'document.immutable' USING ERRCODE = 'check_violation';
END
$$;

ALTER TABLE kernel.document ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.document FORCE ROW LEVEL SECURITY;

-- The template with party_read (a document has a counterparty), plus own_update for the
-- columns issuance fills on a stored draft and for the status.
CREATE POLICY own_read ON kernel.document FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON kernel.document FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON kernel.document FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON kernel.document FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity()
                OR counterparty_entity_id = kernel.scope_entity())
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON kernel.document FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.document FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.document FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.document TO app_rw;
-- What issuance fills on a stored draft, and the status afterwards. The trigger keeps every
-- one of these frozen once issued_at is set, whatever the grant says.
GRANT UPDATE (status, series_id, doc_number, doc_number_display, issued_at, issued_local,
              business_date, operator_user_id, device_id, net_amount, tax_amount, gross_amount,
              content_hash, notes)
    ON kernel.document TO app_rw;

-- ---- The rows of a document: lines, links, history, attachments -------------------------------
--
-- These carry no owner column: the header decides. A row is visible when its document is
-- visible to the caller, under whichever class the caller has, and writable when the caller
-- owns the document in an OWN scope. SECURITY INVOKER, so the header's own policies decide.

CREATE OR REPLACE FUNCTION kernel.document_visible(p_document_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    SELECT EXISTS (SELECT 1 FROM kernel.document d WHERE d.document_id = p_document_id)
$$;

CREATE OR REPLACE FUNCTION kernel.document_owned(p_document_id uuid)
RETURNS boolean
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    SELECT kernel.scope_class() = 'OWN'
       AND EXISTS (SELECT 1
                     FROM kernel.document d
                    WHERE d.document_id = p_document_id
                      AND d.owner_entity_id = kernel.scope_entity())
$$;

CREATE TABLE kernel.document_line (
    document_line_id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES kernel.document (document_id),
    line_no integer NOT NULL
        CHECK (line_no >= 1),
    sku_id uuid,
    batch_id uuid,
    uom_code text,
    qty numeric(14,3) NOT NULL,
    unit_price numeric(14,4),
    mrp_applied numeric(14,4),
    control_price_applied numeric(14,4),
    cap_reason text
        CHECK (cap_reason IS NULL
               OR cap_reason IN ('NONE', 'MRP_LOWEST', 'MRP_BARCODE', 'MRP_PICKED', 'CONTROL_PRICE')),
    discount_rule_id uuid,
    discount_amount numeric(14,2),
    tax_rate_percent numeric(6,3),
    tax_amount numeric(14,2),
    line_total numeric(14,2),
    unit_cost_at_issue numeric(14,4),
    loss_category text,
    reference_line_id uuid REFERENCES kernel.document_line (document_line_id),
    negative_ack boolean,
    picked boolean,
    weight_kg numeric(10,3),
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, line_no)
);

CREATE TRIGGER trg_document_line_insert_only
    BEFORE UPDATE ON kernel.document_line
    FOR EACH ROW EXECUTE FUNCTION kernel.document_rows_insert_only();

CREATE TABLE kernel.document_link (
    from_document_id uuid NOT NULL REFERENCES kernel.document (document_id),
    to_document_id uuid NOT NULL REFERENCES kernel.document (document_id),
    link_type text NOT NULL
        CHECK (link_type IN ('REVERSES', 'CREDITS', 'DEBITS', 'ADJUSTS', 'SETTLES', 'DISPUTES', 'SUPERSEDES')),
    amount numeric(14,2),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (from_document_id, to_document_id, link_type),
    CHECK (from_document_id <> to_document_id),
    -- The amount applied to that original, for the link types that carry one.
    CHECK ((link_type IN ('SETTLES', 'CREDITS', 'DEBITS')) = (amount IS NOT NULL))
);

CREATE INDEX document_link_to_idx ON kernel.document_link (to_document_id);

CREATE TRIGGER trg_document_link_insert_only
    BEFORE UPDATE ON kernel.document_link
    FOR EACH ROW EXECUTE FUNCTION kernel.document_rows_insert_only();

CREATE TABLE kernel.document_state_history (
    history_id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES kernel.document (document_id),
    from_status text,
    to_status text NOT NULL,
    occurred_at timestamptz NOT NULL,
    occurred_local timestamp,
    received_at timestamptz NOT NULL DEFAULT now(),
    actor_user_id uuid,
    device_id uuid,
    reason_code text,
    reason_text text
);

CREATE INDEX document_state_history_document_idx
    ON kernel.document_state_history (document_id, occurred_at);

CREATE TRIGGER trg_document_state_history_insert_only
    BEFORE UPDATE ON kernel.document_state_history
    FOR EACH ROW EXECUTE FUNCTION kernel.document_rows_insert_only();

CREATE TABLE kernel.document_attachment (
    attachment_id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES kernel.document (document_id),
    object_key text NOT NULL,
    content_hash char(64),
    status text NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'COMPLETE', 'FAILED')),
    captured_at timestamptz NOT NULL,
    captured_by uuid,
    device_id uuid,
    received_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX document_attachment_document_idx
    ON kernel.document_attachment (document_id);

DO $$
DECLARE
    child text;
BEGIN
    FOREACH child IN ARRAY ARRAY['document_line', 'document_link', 'document_state_history', 'document_attachment']
    LOOP
        EXECUTE format('ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY', child);
        EXECUTE format('ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY', child);
        EXECUTE format('REVOKE ALL PRIVILEGES ON kernel.%I FROM PUBLIC', child);
        EXECUTE format('GRANT SELECT, INSERT ON kernel.%I TO app_rw', child);
    END LOOP;
END
$$;

CREATE POLICY document_read ON kernel.document_line FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));
CREATE POLICY document_write ON kernel.document_line FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id));

-- A link is the correcting document's row: readable with either side, written by the owner
-- of the correcting document.
CREATE POLICY document_read ON kernel.document_link FOR SELECT TO app_rw
    USING (kernel.document_visible(from_document_id) OR kernel.document_visible(to_document_id));
CREATE POLICY document_write ON kernel.document_link FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(from_document_id));

CREATE POLICY document_read ON kernel.document_state_history FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));
CREATE POLICY document_write ON kernel.document_state_history FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id));

CREATE POLICY document_read ON kernel.document_attachment FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));
CREATE POLICY document_write ON kernel.document_attachment FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id));
-- PENDING -> COMPLETE when the upload is verified (K-09); the owner's business.
CREATE POLICY document_update ON kernel.document_attachment FOR UPDATE TO app_rw
    USING (kernel.document_owned(document_id))
    WITH CHECK (kernel.document_owned(document_id));
GRANT UPDATE (status, content_hash) ON kernel.document_attachment TO app_rw;

-- ---- Density check (C-I2) ---------------------------------------------------------------------
--
-- A series is dense when it holds exactly next_number - 1 issued documents numbered 1 to
-- next_number - 1. A rolled-back issuance takes no number (the UPDATE rolls back with it), so
-- any hole is a finding. Runs under the caller's row-level security: the nightly check reads
-- as FEDERATION_VIEW and sees every series.
CREATE OR REPLACE FUNCTION kernel.numbering_gaps()
RETURNS TABLE (
    series_id uuid,
    prefix varchar(24),
    expected_count bigint,
    found_count bigint,
    first_missing bigint
)
LANGUAGE sql
STABLE
SECURITY INVOKER
AS $$
    WITH counted AS (
        SELECT s.series_id,
               s.prefix,
               s.next_number - 1 AS expected_count,
               (SELECT count(*) FROM kernel.document d WHERE d.series_id = s.series_id) AS found_count
          FROM kernel.numbering_series s
         WHERE s.next_number > 1
    )
    SELECT c.series_id,
           c.prefix,
           c.expected_count,
           c.found_count,
           (SELECT min(n)
              FROM generate_series(1, c.expected_count) AS n
             WHERE NOT EXISTS (SELECT 1 FROM kernel.document d
                                WHERE d.series_id = c.series_id AND d.doc_number = n)) AS first_missing
      FROM counted c
     WHERE c.found_count <> c.expected_count
$$;
