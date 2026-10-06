-- M4-06 claims and M4-10 transfer requests (29 September 2026, demo scope). 24A sections 3 and 6;
-- what differs and why is in the module README ("Claims" and "Transfer requests").
--
-- The rule of the whole module holds here too: no row is written by two parties (AGENTS.md idea
-- 3). The claim is the buyer's document; the seller's decision on it is the seller's own row
-- (claim_decision, claim_decision_line), as the discrepancy's settlement is (V0005); the return of
-- the goods is the buyer's row again (claim_return). A transfer request is written at the shop that
-- asks; the society's decision is a row of its own at the stores that give (transfer_request_decision);
-- M5's transfer names the request (inventory.transfer.transfer_request_id, m5 V0006).

-- ---------------------------------------------------------------------------------------------
-- 1. The claim (CLM, doc 24 section 3.5): the buyer's document, an extension of its kernel
--    document like doc_discrepancy, so it follows the header's policies (the seller reads it as
--    the counterparty). 24A's decision columns (inspected_at, findings, decision, decided_at ...)
--    are the seller's and live in claim_decision below.
CREATE TABLE trading.doc_claim (
    document_id      uuid        PRIMARY KEY,
    grn_document_id  uuid        NOT NULL,
    kind             text        NOT NULL CHECK (kind IN ('DAMAGED', 'EXPIRED_ON_ARRIVAL', 'WRONG_GOODS', 'QUALITY')),
    -- The buyer asks for the goods to go back; the seller decides (claim_decision.return_required).
    return_requested boolean     NOT NULL DEFAULT false,
    note             text,
    window_ends_at   timestamptz NOT NULL
);
CREATE INDEX doc_claim_grn ON trading.doc_claim (grn_document_id);

ALTER TABLE trading.doc_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.doc_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY document_read ON trading.doc_claim FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));
CREATE POLICY document_write ON trading.doc_claim FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id));
GRANT SELECT, INSERT ON trading.doc_claim TO app_rw;

-- The claimed quantity per GRN line, with the batch the GRN registered.
CREATE TABLE trading.doc_claim_line (
    line_id     uuid          PRIMARY KEY,
    document_id uuid          NOT NULL,
    line_no     integer       NOT NULL CHECK (line_no >= 1),
    grn_line_id uuid          NOT NULL,
    sku_id      uuid          NOT NULL,
    batch_id    uuid,
    uom_code    text          NOT NULL,
    claimed_qty numeric(14,3) NOT NULL CHECK (claimed_qty > 0),
    UNIQUE (document_id, grn_line_id)
);
CREATE INDEX doc_claim_line_grn_line ON trading.doc_claim_line (grn_line_id);

ALTER TABLE trading.doc_claim_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.doc_claim_line FORCE ROW LEVEL SECURITY;
CREATE POLICY document_read ON trading.doc_claim_line FOR SELECT TO app_rw
    USING (kernel.document_visible(document_id));
CREATE POLICY document_write ON trading.doc_claim_line FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(document_id));
GRANT SELECT, INSERT ON trading.doc_claim_line TO app_rw;

-- The photographs of the claim (19A section 9): the kernel's attachment rows on the claim
-- document; this remembers which belong to the claim so the decision can ask for them COMPLETE.
CREATE TABLE trading.claim_photo (
    attachment_id     uuid        PRIMARY KEY,
    claim_document_id uuid        NOT NULL,
    added_by          uuid        NOT NULL,
    added_at          timestamptz NOT NULL
);
CREATE INDEX claim_photo_claim ON trading.claim_photo (claim_document_id);

ALTER TABLE trading.claim_photo ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.claim_photo FORCE ROW LEVEL SECURITY;
CREATE POLICY document_read ON trading.claim_photo FOR SELECT TO app_rw
    USING (kernel.document_visible(claim_document_id));
CREATE POLICY document_write ON trading.claim_photo FOR INSERT TO app_rw
    WITH CHECK (kernel.document_owned(claim_document_id));
GRANT SELECT, INSERT ON trading.claim_photo TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 2. The seller's decision: APPROVED (in whole or in part, with the credit note) or REJECTED.
--    The template with party_read, as discrepancy_settlement.
CREATE TABLE trading.claim_decision (
    claim_document_id       uuid        PRIMARY KEY,
    decision                text        NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    findings                text,
    reason                  text,
    return_required         boolean     NOT NULL DEFAULT false,
    credit_note_document_id uuid,
    decided_by              uuid        NOT NULL,
    decided_at              timestamptz NOT NULL,
    owner_entity_id         uuid        NOT NULL,
    counterparty_entity_id  uuid        NOT NULL
);

ALTER TABLE trading.claim_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.claim_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.claim_decision FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON trading.claim_decision FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON trading.claim_decision FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON trading.claim_decision FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.claim_decision FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.claim_decision TO app_rw;

-- What the seller accepted of each claimed line (a partial acceptance accepts less).
CREATE TABLE trading.claim_decision_line (
    claim_line_id          uuid          PRIMARY KEY,
    claim_document_id      uuid          NOT NULL,
    approved_qty           numeric(14,3) NOT NULL CHECK (approved_qty >= 0),
    owner_entity_id        uuid          NOT NULL,
    counterparty_entity_id uuid          NOT NULL
);
CREATE INDEX claim_decision_line_claim ON trading.claim_decision_line (claim_document_id);

ALTER TABLE trading.claim_decision_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.claim_decision_line FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.claim_decision_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON trading.claim_decision_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY party_read ON trading.claim_decision_line FOR SELECT TO app_rw
    USING (kernel.scope_class() IN ('OWN', 'PARTY')
           AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON trading.claim_decision_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.claim_decision_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.claim_decision_line TO app_rw;

-- 3. The buyer sends the goods back (an approved claim with the return required): the buyer's
--    row; M5 posts RETURN_TO_SELLER from the buyer's lot on claim.return_dispatched.v1.
CREATE TABLE trading.claim_return (
    claim_document_id      uuid        PRIMARY KEY,
    location_id            uuid        NOT NULL,
    dispatched_by          uuid        NOT NULL,
    dispatched_at          timestamptz NOT NULL,
    owner_entity_id        uuid        NOT NULL,
    counterparty_entity_id uuid        NOT NULL
);

ALTER TABLE trading.claim_return ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.claim_return FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.claim_return FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON trading.claim_return FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
-- A PARTY session reads both sides, as on every trading table. An OWN session reads here only the
-- returns sent to it (the seller); its own returns it reads through own_read, which keeps a
-- shop-scoped session to its location. "Owner or counterparty" for OWN too would let a shop read
-- every return of its entity (RLS matrix, 29 September 2026).
CREATE POLICY party_read ON trading.claim_return FOR SELECT TO app_rw
    USING ((kernel.scope_class() = 'PARTY'
            AND (owner_entity_id = kernel.scope_entity() OR counterparty_entity_id = kernel.scope_entity()))
           OR (kernel.scope_class() = 'OWN' AND counterparty_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON trading.claim_return FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.claim_return FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.claim_return TO app_rw;

-- 4. A credit note settles at most one claim, as it settles at most one discrepancy (V0005).
ALTER TABLE trading.doc_credit_note ADD COLUMN claim_document_id uuid;
CREATE UNIQUE INDEX doc_credit_note_claim ON trading.doc_credit_note (claim_document_id)
    WHERE claim_document_id IS NOT NULL;

-- ---------------------------------------------------------------------------------------------
-- 5. The transfer request (doc 24 section 4.7), written at the shop that asks (location_id, the
--    destination), naming the stores that should give (from_location_id) when the shop knows
--    them: a shop session reads its own location only (M1), so the society may name the source
--    when it decides. 24A keeps status, decided_by and xfr_document_id on this row, which the
--    society would update; as M5's transfer (m5 V0004) the demo keeps two rows, and the transfer
--    names the request.
CREATE TABLE trading.transfer_request (
    request_id       uuid        PRIMARY KEY,
    owner_entity_id  uuid        NOT NULL,
    location_id      uuid        NOT NULL,
    -- The stores the shop asks, when it knows them; the society names the source when it decides.
    from_location_id uuid,
    reason           text,
    requested_by     uuid        NOT NULL,
    requested_at     timestamptz NOT NULL,
    CHECK (from_location_id IS NULL OR from_location_id <> location_id)
);
CREATE INDEX transfer_request_by_location ON trading.transfer_request (location_id, requested_at);

ALTER TABLE trading.transfer_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.transfer_request FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.transfer_request FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
-- The stores read what is asked of them.
CREATE POLICY source_read ON trading.transfer_request FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND from_location_id = kernel.scope_location());
CREATE POLICY own_write ON trading.transfer_request FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON trading.transfer_request FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.transfer_request FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.transfer_request TO app_rw;

CREATE TABLE trading.transfer_request_line (
    line_id          uuid          PRIMARY KEY,
    request_id       uuid          NOT NULL REFERENCES trading.transfer_request (request_id),
    owner_entity_id  uuid          NOT NULL,
    location_id      uuid          NOT NULL,
    from_location_id uuid,
    line_no          integer       NOT NULL CHECK (line_no >= 1),
    sku_id           uuid          NOT NULL,
    qty              numeric(14,3) NOT NULL CHECK (qty > 0),
    UNIQUE (request_id, line_no)
);

ALTER TABLE trading.transfer_request_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.transfer_request_line FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.transfer_request_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY source_read ON trading.transfer_request_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND from_location_id = kernel.scope_location());
CREATE POLICY own_write ON trading.transfer_request_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON trading.transfer_request_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.transfer_request_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.transfer_request_line TO app_rw;

-- The society's decision, written at the stores (location_id, the source) or entity-wide; the
-- asking shop reads it (dest_read).
CREATE TABLE trading.transfer_request_decision (
    request_id      uuid        PRIMARY KEY REFERENCES trading.transfer_request (request_id),
    owner_entity_id uuid        NOT NULL,
    location_id     uuid        NOT NULL,
    to_location_id  uuid        NOT NULL,
    decision        text        NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reason          text,
    decided_by      uuid        NOT NULL,
    decided_at      timestamptz NOT NULL
);

ALTER TABLE trading.transfer_request_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE trading.transfer_request_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON trading.transfer_request_decision FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY dest_read ON trading.transfer_request_decision FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND to_location_id = kernel.scope_location());
CREATE POLICY own_write ON trading.transfer_request_decision FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON trading.transfer_request_decision FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON trading.transfer_request_decision FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));
GRANT SELECT, INSERT ON trading.transfer_request_decision TO app_rw;
