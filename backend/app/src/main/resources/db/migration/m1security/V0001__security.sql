-- M1 Party, Tenancy & Security
-- security schema foundation


-- =========================================================
-- security.app_user
-- =========================================================

CREATE TABLE security.app_user (
    user_id uuid PRIMARY KEY,

    home_entity_id uuid NOT NULL,

    username varchar(64) NOT NULL UNIQUE,
    display_name text NOT NULL,

    language text NOT NULL DEFAULT 'en',

    user_kind text NOT NULL
        CHECK (
            user_kind IN (
                'BACK_OFFICE',
                'TILL',
                'BOTH',
                'EXTERNAL'
            )
        ),

    provider_subject text UNIQUE,

    pin_hash text,
    pin_changed_at timestamptz,

    pin_history jsonb NOT NULL DEFAULT '[]',

    sandbox boolean NOT NULL DEFAULT false,

    succeeds_user_id uuid,

    status text NOT NULL DEFAULT 'PENDING'
        CHECK (
            status IN (
                'PENDING',
                'ACTIVE',
                'LOCKED',
                'DEACTIVATED'
            )
        ),

    owner_entity_id uuid
        GENERATED ALWAYS AS (home_entity_id) STORED
);


-- =========================================================
-- security.permission
-- =========================================================

CREATE TABLE security.permission (
    permission_code varchar(64) PRIMARY KEY,

    module varchar(16) NOT NULL,

    description_en text NOT NULL,

    offline_allowed boolean NOT NULL DEFAULT false,

    requires_mfa boolean NOT NULL DEFAULT false,

    scope text NOT NULL
        CHECK (
            scope IN (
                'LOCATION',
                'ENTITY',
                'FEDERATION'
            )
        ),

    limits_schema jsonb
);


-- =========================================================
-- security.permission_catalogue_version
-- =========================================================

CREATE TABLE security.permission_catalogue_version (
    rv integer PRIMARY KEY,

    published_at timestamptz NOT NULL DEFAULT now()
);


-- =========================================================
-- security.role
-- =========================================================

CREATE TABLE security.role (
    role_id uuid PRIMARY KEY,

    -- NULL means Federation-owned template.
    owner_entity_id uuid,

    name_en text NOT NULL,
    name_si text,
    name_ta text,

    is_template boolean NOT NULL DEFAULT false,

    role_class text NOT NULL DEFAULT 'OWN'
        CHECK (
            role_class IN (
                'OWN',
                'FEDERATION_VIEW',
                'EXTERNAL_TIMEBOXED'
            )
        ),

    template_role_id uuid
        REFERENCES security.role(role_id),

    template_version_seen integer,

    version integer NOT NULL DEFAULT 1,

    status text NOT NULL DEFAULT 'ACTIVE'
        CHECK (
            status IN (
                'ACTIVE',
                'RETIRED'
            )
        ),

    UNIQUE (
        owner_entity_id,
        name_en
    )
);


-- =========================================================
-- security.role_permission
-- =========================================================

CREATE TABLE security.role_permission (
    role_id uuid NOT NULL
        REFERENCES security.role(role_id),

    permission_code varchar(64) NOT NULL
        REFERENCES security.permission(permission_code),

    limits jsonb,

    PRIMARY KEY (
        role_id,
        permission_code
    )
);


-- =========================================================
-- security.user_role
-- =========================================================

CREATE TABLE security.user_role (
    user_id uuid NOT NULL
        REFERENCES security.app_user(user_id),

    role_id uuid NOT NULL
        REFERENCES security.role(role_id),

    scope_entity_id uuid NOT NULL,

    scope_location_id uuid
);

-- 21A expresses this invariant as a PRIMARY KEY containing
-- COALESCE(), which PostgreSQL does not permit directly.
-- The equivalent unique expression index is used instead.
CREATE UNIQUE INDEX user_role_scope_uq
    ON security.user_role (
        user_id,
        role_id,
        scope_entity_id,
        COALESCE(
            scope_location_id,
            '00000000-0000-0000-0000-000000000000'::uuid
        )
    );


-- =========================================================
-- security.sod_pair
-- =========================================================

CREATE TABLE security.sod_pair (
    sod_pair_id uuid PRIMARY KEY,

    permission_a varchar(64) NOT NULL,
    permission_b varchar(64) NOT NULL,

    mode text NOT NULL
        CHECK (
            mode IN (
                'INSTANCE',
                'ROLE'
            )
        ),

    owner_entity_id uuid,

    CHECK (
        permission_a < permission_b
    ),

    UNIQUE (
        permission_a,
        permission_b,
        owner_entity_id
    )
);


-- =========================================================
-- security.external_grant
-- =========================================================

CREATE TABLE security.external_grant (
    grant_id uuid PRIMARY KEY,

    grantee_user_id uuid NOT NULL
        REFERENCES security.app_user(user_id),

    scope_entity_ids uuid[] NOT NULL,

    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,

    reason text NOT NULL,

    status text NOT NULL DEFAULT 'ACTIVE'
        CHECK (
            status IN (
                'ACTIVE',
                'EXPIRED',
                'REVOKED'
            )
        ),

    -- Owned by Federation.
    owner_entity_id uuid NOT NULL,

    CHECK (
        valid_until <= valid_from + interval '12 months'
    )
);


-- =========================================================
-- Row-level security
-- =========================================================


-- =========================================================
-- security.app_user RLS
-- =========================================================

ALTER TABLE security.app_user
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE security.app_user
    FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read
    ON security.app_user
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_write
    ON security.app_user
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_update
    ON security.app_user
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY fed_view
    ON security.app_user
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'FEDERATION_VIEW'
    );


-- =========================================================
-- security.permission RLS
-- Reference catalogue: authenticated scopes may read only.
-- =========================================================

ALTER TABLE security.permission
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE security.permission
    FORCE ROW LEVEL SECURITY;

CREATE POLICY authenticated_read
    ON security.permission
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() <> 'NONE'
    );


-- =========================================================
-- security.permission_catalogue_version RLS
-- Reference catalogue: authenticated scopes may read only.
-- =========================================================

ALTER TABLE security.permission_catalogue_version
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE security.permission_catalogue_version
    FORCE ROW LEVEL SECURITY;

CREATE POLICY authenticated_read
    ON security.permission_catalogue_version
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() <> 'NONE'
    );


-- =========================================================
-- security.role RLS
-- Entity roles: OWN.
-- Federation templates (owner NULL): readable by authenticated scopes.
-- =========================================================

ALTER TABLE security.role
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE security.role
    FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read
    ON security.role
    FOR SELECT
    TO app_rw
    USING (
        (
            kernel.scope_class() = 'OWN'
            AND owner_entity_id = kernel.scope_entity()
        )
        OR (
            owner_entity_id IS NULL
            AND kernel.scope_class() <> 'NONE'
        )
    );

CREATE POLICY own_write
    ON security.role
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_update
    ON security.role
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY fed_view
    ON security.role
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'FEDERATION_VIEW'
    );


-- =========================================================
-- security.role_permission RLS
-- =========================================================

ALTER TABLE security.role_permission
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE security.role_permission
    FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read
    ON security.role_permission
    FOR SELECT
    TO app_rw
    USING (
        EXISTS (
            SELECT 1
            FROM security.role r
            WHERE r.role_id = role_permission.role_id
              AND (
                  (
                      kernel.scope_class() = 'OWN'
                      AND r.owner_entity_id = kernel.scope_entity()
                  )
                  OR (
                      r.owner_entity_id IS NULL
                      AND kernel.scope_class() <> 'NONE'
                  )
              )
        )
    );

CREATE POLICY own_write
    ON security.role_permission
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND EXISTS (
            SELECT 1
            FROM security.role r
            WHERE r.role_id = role_permission.role_id
              AND r.owner_entity_id = kernel.scope_entity()
        )
    );

CREATE POLICY own_update
    ON security.role_permission
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND EXISTS (
            SELECT 1
            FROM security.role r
            WHERE r.role_id = role_permission.role_id
              AND r.owner_entity_id = kernel.scope_entity()
        )
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND EXISTS (
            SELECT 1
            FROM security.role r
            WHERE r.role_id = role_permission.role_id
              AND r.owner_entity_id = kernel.scope_entity()
        )
    );

CREATE POLICY fed_view
    ON security.role_permission
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'FEDERATION_VIEW'
    );


-- =========================================================
-- security.user_role RLS
-- =========================================================

ALTER TABLE security.user_role
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE security.user_role
    FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read
    ON security.user_role
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND scope_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR scope_location_id = kernel.scope_location()
        )
    );

CREATE POLICY own_write
    ON security.user_role
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND scope_entity_id = kernel.scope_entity()
        AND (
            kernel.scope_location() IS NULL
            OR scope_location_id = kernel.scope_location()
        )
    );

CREATE POLICY fed_view
    ON security.user_role
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'FEDERATION_VIEW'
    );


-- =========================================================
-- security.sod_pair RLS
-- Federation/default rows (owner NULL) are reference rows.
-- =========================================================

ALTER TABLE security.sod_pair
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE security.sod_pair
    FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read
    ON security.sod_pair
    FOR SELECT
    TO app_rw
    USING (
        (
            kernel.scope_class() = 'OWN'
            AND owner_entity_id = kernel.scope_entity()
        )
        OR (
            owner_entity_id IS NULL
            AND kernel.scope_class() <> 'NONE'
        )
    );

CREATE POLICY own_write
    ON security.sod_pair
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_update
    ON security.sod_pair
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY fed_view
    ON security.sod_pair
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'FEDERATION_VIEW'
    );


-- =========================================================
-- security.external_grant RLS
-- =========================================================

ALTER TABLE security.external_grant
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE security.external_grant
    FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read
    ON security.external_grant
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_write
    ON security.external_grant
    FOR INSERT
    TO app_rw
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY own_update
    ON security.external_grant
    FOR UPDATE
    TO app_rw
    USING (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    )
    WITH CHECK (
        kernel.scope_class() = 'OWN'
        AND owner_entity_id = kernel.scope_entity()
    );

CREATE POLICY fed_view
    ON security.external_grant
    FOR SELECT
    TO app_rw
    USING (
        kernel.scope_class() = 'FEDERATION_VIEW'
    );


-- =========================================================
-- Grants
-- =========================================================

GRANT SELECT, INSERT, UPDATE
    ON security.app_user,
       security.role,
       security.role_permission,
       security.sod_pair,
       security.external_grant
    TO app_rw;

-- Revocation DELETE is intentionally not granted here.
-- The audited M1 revoke path is implemented later.
GRANT SELECT, INSERT
    ON security.user_role
    TO app_rw;

-- Read-only reference catalogue.
GRANT SELECT
    ON security.permission,
       security.permission_catalogue_version
    TO app_rw;