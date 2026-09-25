-- Development seed for the users of the dev realm (infra/compose/realm-dev.json): the four
-- users the realm signs in, as rows of the security schema, each with one development role
-- that holds every permission of the catalogue in the user's home entity. Loaded by
-- `make seed`, which runs it as the PostgreSQL superuser after the backend has started (so
-- the permission catalogue of M1 is already there). Safe to run again.
--
-- Why: K-02 puts the token's user behind every request, and K-03b resolves the permission of
-- every command from these tables. The user ids are the `id` of each realm user, which is
-- what the token's `sub` carries; without these rows every command in the local stack would
-- be refused once permissions are enforced (compose: COOP_ERP_ENFORCE_PERMISSIONS).
--
--   0190f000-0000-7000-8000-0000000000a1  fed-admin    Federation   FEDERATION_VIEW in the token
--   0190f000-0000-7000-8000-0000000000a2  fed-officer  Federation   OWN
--   0190f000-0000-7000-8000-0000000000a3  mpcs-admin   MPCS M001    OWN
--   0190f000-0000-7000-8000-0000000000a4  cashier      MPCS M001    OWN
--
-- "Every permission" is a development convenience, not a role anybody would hold in
-- production: the roles of a real entity come from the templates of seed/m1party/role-templates.yaml
-- through M1's role management (M1-08). fed-admin's token carries the FEDERATION_VIEW class,
-- which resolves to no permission at all (K-03b), so its role here is for the day the class is
-- switched; it is harmless until then.

-- The hello module's permission is the template module's and not in M1's catalogue
-- (seed/m1party/permissions.yaml); the development stack needs it for the smoke test and the
-- browser tests that register a greeting.
INSERT INTO security.permission (permission_code, module, description_en, offline_allowed, requires_mfa, scope)
VALUES ('hello.greeting.register', 'hello', 'Register a greeting (template module)', false, false, 'ENTITY')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO security.app_user (user_id, home_entity_id, username, display_name, language, user_kind, status)
VALUES
    ('0190f000-0000-7000-8000-0000000000a1', '0190f000-0000-7000-8000-000000000001', 'fed-admin',   'Federation Admin',   'en', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000a2', '0190f000-0000-7000-8000-000000000001', 'fed-officer', 'Federation Officer', 'en', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000a3', '0190f000-0000-7000-8000-000000000002', 'mpcs-admin',  'MPCS Admin',         'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000a4', '0190f000-0000-7000-8000-000000000002', 'cashier',     'Shop Cashier',       'ta', 'BACK_OFFICE', 'ACTIVE')
ON CONFLICT (user_id) DO NOTHING;

-- One development role per entity, class OWN, every permission of the catalogue.
INSERT INTO security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)
VALUES
    ('0190f000-0000-7000-8000-0000000000b1', '0190f000-0000-7000-8000-000000000001', 'Developer (every permission)', false, 'OWN', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000b2', '0190f000-0000-7000-8000-000000000002', 'Developer (every permission)', false, 'OWN', 'ACTIVE')
ON CONFLICT (role_id) DO NOTHING;

INSERT INTO security.role_permission (role_id, permission_code)
SELECT r.role_id, p.permission_code
FROM security.role r
CROSS JOIN security.permission p
WHERE r.role_id IN ('0190f000-0000-7000-8000-0000000000b1', '0190f000-0000-7000-8000-0000000000b2')
ON CONFLICT DO NOTHING;

-- Entity-wide assignments: they apply at every location of the entity (doc 19 section 3.1).
INSERT INTO security.user_role (user_id, role_id, scope_entity_id, scope_location_id)
VALUES
    ('0190f000-0000-7000-8000-0000000000a1', '0190f000-0000-7000-8000-0000000000b1', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f000-0000-7000-8000-0000000000a2', '0190f000-0000-7000-8000-0000000000b1', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f000-0000-7000-8000-0000000000a3', '0190f000-0000-7000-8000-0000000000b2', '0190f000-0000-7000-8000-000000000002', NULL),
    ('0190f000-0000-7000-8000-0000000000a4', '0190f000-0000-7000-8000-0000000000b2', '0190f000-0000-7000-8000-000000000002', NULL)
ON CONFLICT DO NOTHING;
