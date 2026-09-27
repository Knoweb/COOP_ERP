-- M6 minimal (demo phase 3, the shop): what central keeps of a till's sessions and receipts
-- (26A section 10, "ingest/ ReceiptBundleHook ... SessionHook"; doc 26 sections 3 and 4.2).
--
--   till_session         a session the till opened (its float), as the till reported it
--   till_session_close   the same session's close (counted, expected, variance), a row of its own
--   receipt              a receipt the till issued offline from its own series (RCT, TILL_POSITION),
--                        with the number, device sequence and content hash it was issued with
--   receipt_line         its lines, with the batch the till resolved (M5 deducts from it)
--   receipt_tender       its tenders
--
-- Every row is the till's fact (AGENTS.md idea 3): written once by the ingest consumer, in the
-- device's OWN scope at its shop, and never changed. A close is a row of its own rather than an
-- update of the open. What central found odd about a fact is kept on the receipt (flags) at the
-- moment it is written: a fact is never refused for a business reason, it is applied and flagged.
--
-- Departure from 26A (module README): the receipt's header and lines belong in the kernel's
-- document base with origin OFFLINE (doc_receipt extending it). The kernel's bundle handler that
-- writes an offline document there is K-08-F4 (PLAN_TO_M2), not built yet; until then M6 keeps
-- the issued receipt, with its document identity, here.

-- ---------------------------------------------------------------------------------------------
-- 1. Sessions
CREATE TABLE pos.till_session (
    session_id       uuid          PRIMARY KEY,
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    till_position_id uuid,
    device_id        uuid,
    operator_user_id uuid,
    business_date    date,
    opened_at        timestamptz   NOT NULL,
    float_amount     numeric(14,2) NOT NULL DEFAULT 0,
    recorded_at      timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX till_session_by_location ON pos.till_session (location_id, opened_at);

CREATE TABLE pos.till_session_close (
    session_id      uuid          PRIMARY KEY,
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    device_id       uuid,
    closed_by       uuid,
    closed_at       timestamptz   NOT NULL,
    counted_cash    numeric(14,2),
    expected_cash   numeric(14,2),
    variance        numeric(14,2),
    recorded_at     timestamptz   NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------
-- 2. Receipts
CREATE TABLE pos.receipt (
    document_id        uuid          PRIMARY KEY,
    owner_entity_id    uuid          NOT NULL,
    location_id        uuid          NOT NULL,
    till_position_id   uuid,
    device_id          uuid,
    session_id         uuid,
    doc_type_code      text          NOT NULL DEFAULT 'RCT',
    series_id          uuid,
    doc_number         bigint,
    doc_number_display text,
    issued_at          timestamptz   NOT NULL,
    business_date      date,
    operator_user_id   uuid,
    currency           text          NOT NULL DEFAULT 'LKR',
    net_amount         numeric(14,2),
    tax_amount         numeric(14,2),
    gross_amount       numeric(14,2),
    content_hash       text,
    device_seq         bigint,
    origin             text          NOT NULL DEFAULT 'OFFLINE' CHECK (origin IN ('ONLINE', 'OFFLINE')),
    -- What central found odd, never a reason to refuse (LOCATION_MISMATCH, SESSION_UNKNOWN ...).
    flags              text[]        NOT NULL DEFAULT '{}',
    recorded_at        timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX receipt_by_location ON pos.receipt (location_id, issued_at);

CREATE TABLE pos.receipt_line (
    document_id     uuid          NOT NULL REFERENCES pos.receipt (document_id),
    line_no         integer       NOT NULL CHECK (line_no >= 1),
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    sku_id          uuid,
    batch_id        uuid,
    uom_code        text,
    qty             numeric(14,3) NOT NULL,
    unit_price      numeric(14,4),
    line_total      numeric(14,2),
    PRIMARY KEY (document_id, line_no)
);

CREATE TABLE pos.receipt_tender (
    document_id     uuid          NOT NULL REFERENCES pos.receipt (document_id),
    seq             integer       NOT NULL CHECK (seq >= 1),
    owner_entity_id uuid          NOT NULL,
    location_id     uuid          NOT NULL,
    kind            text          NOT NULL,
    amount          numeric(14,2) NOT NULL,
    PRIMARY KEY (document_id, seq)
);

-- ---------------------------------------------------------------------------------------------
-- 3. Row-level security: the template on every table (17A section 6.3), insert-only.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['pos.till_session', 'pos.till_session_close', 'pos.receipt', 'pos.receipt_line',
                             'pos.receipt_tender']
    LOOP
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY own_read ON %s FOR SELECT TO app_rw
            USING (kernel.scope_class() = 'OWN'
                   AND owner_entity_id = kernel.scope_entity()
                   AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))$p$, t);
        EXECUTE format($p$CREATE POLICY own_write ON %s FOR INSERT TO app_rw
            WITH CHECK (kernel.scope_class() = 'OWN'
                        AND owner_entity_id = kernel.scope_entity()
                        AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))$p$, t);
        EXECUTE format($p$CREATE POLICY fed_view ON %s FOR SELECT TO app_rw
            USING (kernel.scope_class() = 'FEDERATION_VIEW')$p$, t);
        EXECUTE format($p$CREATE POLICY ext_view ON %s FOR SELECT TO app_rw
            USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()))$p$, t);
        EXECUTE format('GRANT SELECT, INSERT ON %s TO app_rw', t);
    END LOOP;
END
$$;
