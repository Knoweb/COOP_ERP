-- M9 Integration (29A section 3), the accounting export and the notifications of the demo scope.
-- The integration schema itself is created by the kernel baseline.
--
-- Built here: journal_posting (an M9 table beside 29A's: the postings of M4's
-- journal.postings_ready.v1 as they arrive), journal_export, journal_line, notification_template,
-- notification_rule, and notification_contact (an M9 table beside 29A's: who at an entity hears of
-- a rule, since M1 keeps no telephone or e-mail of its users, doc 21 section 9.3). Deferred with
-- their tickets, recorded in the module README: api_client, api_credential_history, api_scope,
-- api_call_log, posting_map_registry, provider_config, e_invoice_submission, webhook_subscription.
-- Decided on the architect's delegation, 29 September 2026.

-- ---------------------------------------------------------------------------------------------
-- journal_posting: one row per posting of an issued document, from journal.postings_ready.v1.
-- The owning module computed the amount and named the two account roles (its posting map); M9
-- only places it (29A section 6.2: "M9 never recomputes an amount"). Insert only: a redelivered
-- event finds its rows by (document_id, seq) and adds nothing.
CREATE TABLE integration.journal_posting (
    posting_id         uuid          PRIMARY KEY,
    owner_entity_id    uuid          NOT NULL,
    document_id        uuid          NOT NULL,
    seq                int           NOT NULL CHECK (seq > 0),
    doc_type_code      varchar(8)    NOT NULL,
    doc_number_display text          NOT NULL,
    line_kind          text          NOT NULL,
    side               text          NOT NULL CHECK (side IN ('SELLER', 'BUYER')),
    debit_role         text          NOT NULL,
    credit_role        text          NOT NULL,
    amount_source      text          NOT NULL,
    amount             numeric(14,2) NOT NULL CHECK (amount > 0),
    business_date      date          NOT NULL,
    recorded_at        timestamptz   NOT NULL,
    CONSTRAINT journal_posting_document_seq_uq UNIQUE (document_id, seq)
);

CREATE INDEX journal_posting_by_owner_date ON integration.journal_posting (owner_entity_id, business_date);

-- ---------------------------------------------------------------------------------------------
-- journal_export: a run of the export for one entity and one period (29A section 3). Generated in
-- the request (the demo's volume), so REQUESTED is never seen; FAILED, ACKNOWLEDGED and
-- SUPERSEDED stay in the check for the tickets that write them.
CREATE TABLE integration.journal_export (
    export_id       uuid          PRIMARY KEY,
    owner_entity_id uuid          NOT NULL,
    period_from     date          NOT NULL,
    period_to       date          NOT NULL,
    format          text          NOT NULL CHECK (format IN ('JSON', 'CSV', 'ADAPTER')),
    provisional     boolean       NOT NULL DEFAULT false,
    status          text          NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'GENERATED', 'FAILED', 'ACKNOWLEDGED', 'SUPERSEDED')),
    line_count      int           NOT NULL,
    total_debit     numeric(16,2) NOT NULL,
    total_credit    numeric(16,2) NOT NULL,
    content_hash    char(64)      NOT NULL,
    generated_at    timestamptz   NOT NULL,
    requested_by    uuid          NOT NULL,
    requested_at    timestamptz   NOT NULL,
    CHECK (period_from <= period_to)
);

CREATE INDEX journal_export_by_owner ON integration.journal_export (owner_entity_id, requested_at);

-- ---------------------------------------------------------------------------------------------
-- journal_line: the lines of an export (29A section 3), each one posting; posting_id is unique
-- across all exports, which is what makes each posting exported once (a later export over the
-- same period takes only what no earlier one took). Never updated.
CREATE TABLE integration.journal_line (
    line_id                uuid          PRIMARY KEY,
    export_id              uuid          NOT NULL REFERENCES integration.journal_export,
    seq                    int           NOT NULL,
    posting_id             uuid          NOT NULL REFERENCES integration.journal_posting,
    document_id            uuid          NOT NULL,
    doc_type_code          varchar(8)    NOT NULL,
    doc_number_display     text          NOT NULL,
    line_kind              text          NOT NULL,
    side                   text          NOT NULL,
    debit_role             text          NOT NULL,
    credit_role            text          NOT NULL,
    amount                 numeric(14,2) NOT NULL CHECK (amount > 0),
    business_date          date          NOT NULL,
    counterparty_entity_id uuid,
    reference              text,
    owner_entity_id        uuid          NOT NULL,
    CONSTRAINT journal_line_export_seq_uq UNIQUE (export_id, seq),
    CONSTRAINT journal_line_posting_uq UNIQUE (posting_id)
);

-- ---------------------------------------------------------------------------------------------
-- notification_template (29A section 3): the texts in three languages, ICU MessageFormat over the
-- placeholders of the event payload. Federation-owned rows (owner null) are read by everyone.
CREATE TABLE integration.notification_template (
    template_id         varchar(48) PRIMARY KEY,
    owner_entity_id     uuid,
    channel             text        NOT NULL CHECK (channel IN ('SMS', 'EMAIL', 'IN_APP')),
    subject_en          text,
    subject_si          text,
    subject_ta          text,
    body_en             text        NOT NULL,
    body_si             text,
    body_ta             text,
    placeholders_schema jsonb       NOT NULL,
    status              text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    version             int         NOT NULL DEFAULT 1
);

-- notification_rule (29A section 3): which event, which template, who, on which channels.
CREATE TABLE integration.notification_rule (
    rule_id         uuid        PRIMARY KEY,
    owner_entity_id uuid,
    event_type      text        NOT NULL,
    predicate       jsonb,
    template_id     varchar(48) NOT NULL REFERENCES integration.notification_template,
    audience_kind   text        NOT NULL
        CHECK (audience_kind IN ('ROLE_AT_COUNTERPARTY', 'ROLE_AT_OWNER', 'CUSTOMER', 'EXPLICIT')),
    audience_spec   jsonb       NOT NULL,
    channels        text[]      NOT NULL,
    priority        smallint    NOT NULL DEFAULT 100,
    status          text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    name            text        NOT NULL
);

CREATE INDEX notification_rule_by_event ON integration.notification_rule (event_type) WHERE status = 'ACTIVE';

-- notification_contact: who at an entity is reached for a role code of a rule's audience
-- (ACCOUNTS, MANAGER, ...), on one channel, in one language. The address never leaves this table:
-- the kernel's log holds its hash (ADR-27), and no audit row or event carries it.
CREATE TABLE integration.notification_contact (
    contact_id      uuid    PRIMARY KEY,
    owner_entity_id uuid    NOT NULL,
    role_code       text    NOT NULL,
    channel         text    NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
    address         text    NOT NULL CHECK (btrim(address) <> ''),
    language        char(2) NOT NULL DEFAULT 'en' CHECK (language IN ('en', 'si', 'ta')),
    status          text    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT notification_contact_uq UNIQUE (owner_entity_id, role_code, channel, address)
);

-- ---------------------------------------------------------------------------------------------
-- Row-level security (17A section 6.3, db/migration/RLS_POLICY_TEMPLATE.md).

ALTER TABLE integration.journal_posting ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.journal_posting FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON integration.journal_posting FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.journal_posting FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.journal_posting FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.journal_posting FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

ALTER TABLE integration.journal_export ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.journal_export FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON integration.journal_export FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.journal_export FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.journal_export FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.journal_export FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

ALTER TABLE integration.journal_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.journal_line FORCE ROW LEVEL SECURITY;
CREATE POLICY own_read ON integration.journal_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.journal_line FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.journal_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.journal_line FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- Templates and rules are read by the kernel's dispatcher in the scope of whichever entity's
-- event it is matching, and by the renderer after the commit with no scope at all: reference data
-- every session reads. A status change is the Federation's (SetNotificationRuleStatus).
ALTER TABLE integration.notification_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.notification_template FORCE ROW LEVEL SECURITY;
CREATE POLICY everyone_reads ON integration.notification_template FOR SELECT TO app_rw
    USING (true);

ALTER TABLE integration.notification_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.notification_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY everyone_reads ON integration.notification_rule FOR SELECT TO app_rw
    USING (true);
CREATE POLICY federation_status ON integration.notification_rule FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND kernel.scope_entity() = kernel.system_entity()
           AND owner_entity_id IS NULL)
    WITH CHECK (kernel.scope_class() = 'OWN' AND kernel.scope_entity() = kernel.system_entity()
                AND owner_entity_id IS NULL);

-- A contact is read by the audience resolution in the scope of the event's owner, which for a
-- rule addressed to the counterparty is another entity: the buyer's accounts desk is reached on
-- the seller's invoice. An entity's business contacts are directory data, like the entity
-- register; the rows are read by M9's resolver only and served by no operation.
ALTER TABLE integration.notification_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.notification_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY directory_read ON integration.notification_contact FOR SELECT TO app_rw
    USING (true);

REVOKE ALL PRIVILEGES ON integration.journal_posting, integration.journal_export, integration.journal_line,
    integration.notification_template, integration.notification_rule, integration.notification_contact
    FROM PUBLIC;
GRANT SELECT, INSERT ON integration.journal_posting TO app_rw;
GRANT SELECT, INSERT ON integration.journal_export TO app_rw;
GRANT SELECT, INSERT ON integration.journal_line TO app_rw;
GRANT SELECT ON integration.notification_template TO app_rw;
GRANT SELECT ON integration.notification_rule TO app_rw;
GRANT UPDATE (status) ON integration.notification_rule TO app_rw;
GRANT SELECT ON integration.notification_contact TO app_rw;
