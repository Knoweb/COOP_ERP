-- M1 Party, Tenancy & Security
-- security schema foundation


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


CREATE TABLE security.permission_catalogue_version (
    rv integer PRIMARY KEY,

    published_at timestamptz NOT NULL DEFAULT now()
);


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

    UNIQUE (owner_entity_id, name_en)
);


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


CREATE TABLE security.user_role (
    user_id uuid NOT NULL
        REFERENCES security.app_user(user_id),

    role_id uuid NOT NULL
        REFERENCES security.role(role_id),

    scope_entity_id uuid NOT NULL,

    scope_location_id uuid
);

-- 21A expresses this invariant as a PRIMARY KEY containing COALESCE().
-- PostgreSQL does not allow expressions directly inside a PRIMARY KEY,
-- so use the equivalent unique expression index.
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

    CHECK (permission_a < permission_b),

    UNIQUE (
        permission_a,
        permission_b,
        owner_entity_id
    )
);


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