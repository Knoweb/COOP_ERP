-- K-11: the configuration register (19A section 11; doc 19 section 8). Lane C.
--
-- config_item is the register: what may be configured, its type and schema, its default, the
-- finest scope it may be set at, who may change it. Reference data, seeded from
-- seed/kernel/config-items.yaml on every start (items, never values). config_value is
-- append-only: a change is a new row, the current value is the latest effective one, and the
-- most specific scope wins (location, then entity, then the federation default). A row with no
-- scope entity is the federation-wide value.

CREATE TABLE kernel.config_item (
    key text PRIMARY KEY,
    value_type text NOT NULL
        CHECK (value_type IN ('STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DURATION', 'JSON')),
    schema jsonb NOT NULL DEFAULT '{}'::jsonb,
    description_en text NOT NULL,
    description_si text,
    description_ta text,
    default_value jsonb NOT NULL,
    scope_kind text NOT NULL
        CHECK (scope_kind IN ('FEDERATION', 'ENTITY', 'LOCATION')),
    change_permission text NOT NULL,
    sensitive boolean NOT NULL DEFAULT false,
    till_visible boolean NOT NULL DEFAULT false,
    module text NOT NULL DEFAULT 'kernel'
);

CREATE TABLE kernel.config_value (
    key text NOT NULL REFERENCES kernel.config_item (key),
    scope_entity_id uuid,
    scope_location_id uuid,
    value jsonb NOT NULL,
    effective_from timestamptz NOT NULL DEFAULT now(),
    changed_by uuid,
    changed_at timestamptz NOT NULL DEFAULT now(),
    reason text,
    -- A location value belongs to an entity; a federation value belongs to nobody.
    CHECK (scope_location_id IS NULL OR scope_entity_id IS NOT NULL)
);

-- The key 19A writes as a primary key with coalesce(): an expression index says the same.
CREATE UNIQUE INDEX config_value_scope_idx
    ON kernel.config_value (
        key,
        coalesce(scope_entity_id, '00000000-0000-0000-0000-000000000000'::uuid),
        coalesce(scope_location_id, '00000000-0000-0000-0000-000000000000'::uuid),
        effective_from
    );

-- ---- policies ---------------------------------------------------------------------------------

ALTER TABLE kernel.config_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.config_item FORCE ROW LEVEL SECURITY;
CREATE POLICY reference_read ON kernel.config_item FOR SELECT TO app_rw USING (true);
CREATE POLICY seed_reference ON kernel.config_item FOR ALL TO app_seed USING (true) WITH CHECK (true);
REVOKE ALL PRIVILEGES ON kernel.config_item FROM PUBLIC;
GRANT SELECT ON kernel.config_item TO app_rw;
GRANT SELECT, INSERT, UPDATE ON kernel.config_item TO app_seed;

ALTER TABLE kernel.config_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.config_value FORCE ROW LEVEL SECURITY;

-- Reading resolves a value, so a caller sees the federation rows (nobody's), its entity's rows,
-- and, at a location, that location's rows and the entity-wide ones. The template's own_read,
-- widened by the federation rows and the entity-wide rows a location-scoped caller inherits.
CREATE POLICY own_read ON kernel.config_value FOR SELECT TO app_rw
    USING (scope_entity_id IS NULL
           OR (kernel.scope_class() = 'OWN'
               AND scope_entity_id = kernel.scope_entity()
               AND (kernel.scope_location() IS NULL
                    OR scope_location_id IS NULL
                    OR scope_location_id = kernel.scope_location())));
-- An entity writes its own values; the federation-wide row is written in the OWN scope of the
-- Federation, which the register (ConfigRegistry.set) checks against the system entity.
CREATE POLICY own_write ON kernel.config_value FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND (scope_entity_id IS NULL OR scope_entity_id = kernel.scope_entity()));
CREATE POLICY fed_view ON kernel.config_value FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.config_value FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND (scope_entity_id IS NULL OR scope_entity_id = ANY (kernel.granted_entities())));

REVOKE ALL PRIVILEGES ON kernel.config_value FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.config_value TO app_rw;
