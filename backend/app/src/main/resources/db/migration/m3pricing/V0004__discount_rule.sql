-- M3-05 (23A section 3; doc 23 section 3.3): the discount rules of a society, the only way a
-- price is reduced at the till (ADR-14). The predicate and the benefit are JSON of the closed
-- vocabulary of doc 23 section 3.3 (no expression language); the handlers check them per kind
-- (RuleVocabulary) before they are stored.
CREATE TABLE pricing.discount_rule (
    rule_id              uuid        PRIMARY KEY,
    owner_entity_id      uuid        NOT NULL,
    name                 text        NOT NULL CHECK (btrim(name) <> ''),
    kind                 text        NOT NULL CHECK (kind IN
                                         ('TIME_LIMITED_PRICE', 'QUANTITY_BREAK', 'BILL_THRESHOLD', 'FREE_ITEM', 'EXPIRY_MARKDOWN')),
    predicate            jsonb       NOT NULL,
    benefit              jsonb       NOT NULL,
    priority             smallint    NOT NULL DEFAULT 100,
    advisory             boolean     NOT NULL DEFAULT false,
    adopted_from_rule_id uuid,
    valid_from           date        NOT NULL,
    valid_to             date,
    status               text        NOT NULL DEFAULT 'DRAFT'
                                     CHECK (status IN ('DRAFT', 'ACTIVE', 'EXPIRED', 'WITHDRAWN')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE INDEX rule_active ON pricing.discount_rule (owner_entity_id, status, valid_from, valid_to);

-- Row-level security: the template of db/migration/RLS_POLICY_TEMPLATE.md (no location column).
-- Advisory rules, which 23A makes readable by every scope, are deferred for the demo (the column
-- stays false), so no wider read policy exists yet.
ALTER TABLE pricing.discount_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.discount_rule FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON pricing.discount_rule FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON pricing.discount_rule FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON pricing.discount_rule FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON pricing.discount_rule FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON pricing.discount_rule FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- A rule changes status (activate, withdraw) and nothing else; it is never deleted.
GRANT SELECT, INSERT ON pricing.discount_rule TO app_rw;
GRANT UPDATE (status) ON pricing.discount_rule TO app_rw;
