-- PRC-03 / 23A section 3.6: one queued shelf-label print request. The request fields are
-- immutable; the worker records only its outcome. A label job belongs to the society and the
-- shop whose shelves receive the labels.
CREATE TABLE pricing.label_job (
    job_id          uuid        PRIMARY KEY,
    owner_entity_id uuid        NOT NULL,
    location_id     uuid        NOT NULL,
    template        text        NOT NULL,
    sku_ids         uuid[]      NOT NULL CHECK (cardinality(sku_ids) > 0),
    refused         jsonb       NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(refused) = 'array'),
    object_key      text,
    status          text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RENDERED', 'FAILED')),
    created_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX label_job_queued ON pricing.label_job (created_at, job_id) WHERE status = 'QUEUED';

ALTER TABLE pricing.label_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing.label_job FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON pricing.label_job FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON pricing.label_job FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON pricing.label_job FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON pricing.label_job FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON pricing.label_job FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON pricing.label_job TO app_rw;
GRANT UPDATE (status, refused, object_key) ON pricing.label_job TO app_rw;
