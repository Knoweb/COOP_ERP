-- doc 31 section 4 (TILL-PLAT-07): application release registry and device rollout rings.
-- Releases are published centrally by Federation (DevOps); rings govern progressive rollout.

CREATE TABLE kernel.app_release (
    release_id uuid PRIMARY KEY,
    owner_entity_id uuid NOT NULL,
    version varchar(32) NOT NULL DEFAULT '1.0.0',
    platform varchar(32) NOT NULL DEFAULT 'ANDROID',
    package_path text NOT NULL DEFAULT 'releases/package.bin',
    package_hash varchar(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
    package_size_bytes bigint NOT NULL DEFAULT 0,
    min_supported_version varchar(32) NOT NULL DEFAULT '1.0.0',
    target_ring integer NOT NULL DEFAULT 3,
    status varchar(20) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'PAUSED', 'REVOKED')),
    published_at timestamptz NOT NULL DEFAULT now(),
    published_by text NOT NULL DEFAULT 'system',
    paused_at timestamptz,
    paused_by text,
    notes text,
    metadata jsonb,
    CONSTRAINT app_release_version_platform_uniq UNIQUE (version, platform)
);

CREATE INDEX app_release_platform_status_idx
    ON kernel.app_release (platform, status, target_ring);

ALTER TABLE kernel.app_release ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.app_release FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.app_release FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_write ON kernel.app_release FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND owner_entity_id = (SELECT kernel.system_entity()));

CREATE POLICY own_update ON kernel.app_release FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND owner_entity_id = (SELECT kernel.system_entity()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND owner_entity_id = (SELECT kernel.system_entity()));

CREATE POLICY fed_view ON kernel.app_release FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON kernel.app_release FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

CREATE POLICY everyone_reads ON kernel.app_release FOR SELECT TO app_rw
    USING (kernel.scope_class() <> 'NONE'
           AND owner_entity_id = (SELECT kernel.system_entity()));

REVOKE ALL PRIVILEGES ON kernel.app_release FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON kernel.app_release TO app_rw;


CREATE TABLE kernel.device_rollout_ring (
    device_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_entity_id uuid NOT NULL,
    ring integer NOT NULL DEFAULT 3 CHECK (ring >= 0 AND ring <= 3),
    assigned_at timestamptz NOT NULL DEFAULT now(),
    assigned_by text NOT NULL DEFAULT 'system'
);

ALTER TABLE kernel.device_rollout_ring ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.device_rollout_ring FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON kernel.device_rollout_ring FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_write ON kernel.device_rollout_ring FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY own_update ON kernel.device_rollout_ring FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());

CREATE POLICY fed_view ON kernel.device_rollout_ring FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');

CREATE POLICY ext_view ON kernel.device_rollout_ring FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.device_rollout_ring FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON kernel.device_rollout_ring TO app_rw;
