-- M2-07 / CAT-01: Duplicate suspect registry (22A section 3, section 6.1; doc 22 section 6.2).
--
-- Records candidate duplicate SKU pairs flagged by the duplicate scoring engine during
-- bulk import or SKU creation, with similarity score, reasons, and resolution workflow
-- (OPEN -> MERGED | DISMISSED).

CREATE TABLE catalogue.duplicate_suspect (
    suspect_id       uuid PRIMARY KEY,
    sku_a            uuid NOT NULL REFERENCES catalogue.sku (sku_id),
    sku_b            uuid NOT NULL REFERENCES catalogue.sku (sku_id),
    score            numeric(4,3) NOT NULL,
    reasons          text[] NOT NULL,
    status           text NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'MERGED', 'DISMISSED')),
    decided_by       uuid,
    decided_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    owner_entity_id  uuid NOT NULL,
    CHECK (sku_a < sku_b),
    UNIQUE (sku_a, sku_b)
);

CREATE INDEX duplicate_suspect_owner_status_idx
    ON catalogue.duplicate_suspect (owner_entity_id, status);

CREATE INDEX duplicate_suspect_pair_idx
    ON catalogue.duplicate_suspect (sku_a, sku_b);

ALTER TABLE catalogue.duplicate_suspect ENABLE ROW LEVEL SECURITY;
ALTER TABLE catalogue.duplicate_suspect FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON catalogue.duplicate_suspect FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_write ON catalogue.duplicate_suspect FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_update ON catalogue.duplicate_suspect FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY fed_view ON catalogue.duplicate_suspect FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON catalogue.duplicate_suspect FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON catalogue.duplicate_suspect FROM PUBLIC;
GRANT SELECT, INSERT ON catalogue.duplicate_suspect TO app_rw;
GRANT UPDATE (status, decided_by, decided_at) ON catalogue.duplicate_suspect TO app_rw;
