-- Development seed for the users of the dev realm (infra/compose/realm-dev.json): the four
-- users the realm signs in, as rows of the security schema, each with one development role
-- in the user's home entity. Loaded by `make seed`, which runs it as the PostgreSQL superuser
-- after the backend has started (so the permission catalogue of M1 is already there). Safe to
-- run again.
--
-- Why: K-02 puts the token's user behind every request, K-03b resolves the permission of every
-- command from these tables, and since 27 September 2026 (CR-19A-8) the kernel resolves the
-- permission of every read the same way and hands the web shell the resolved set
-- (GET /v1/session), so what a user SEES in the back office is what these rows say. The user
-- ids are the `id` of each realm user, which is what the token's `sub` carries; without these
-- rows every request in the local stack would be refused once permissions are enforced
-- (compose: COOP_ERP_ENFORCE_PERMISSIONS).
--
--   0190f000-0000-7000-8000-0000000000a1  fed-admin    Federation   FEDERATION_VIEW in the token
--   0190f000-0000-7000-8000-0000000000a2  fed-officer  Federation   OWN, every permission
--   0190f000-0000-7000-8000-0000000000a3  mpcs-admin   MPCS M001    OWN, every permission
--   0190f000-0000-7000-8000-0000000000a4  cashier      MPCS M001    OWN, the reads only
--
-- "Every permission" is a development convenience, not a role anybody would hold in
-- production: the roles of a real entity come from the templates of seed/m1party/role-templates.yaml
-- through M1's role management (M1-08). The cashier holds the reads of the catalogue and no
-- command, so that the browser tests have one user who is shown a screen without its form
-- (web/e2e/roles-and-scope.spec.ts). fed-admin's token carries the FEDERATION_VIEW class, which
-- resolves to every read of every slice and no command (K-03b, CR-19A-8), whatever its rows say;
-- its role here is for the day the class is switched.

-- The hello module's permissions are the template module's and not in M1's catalogue
-- (seed/m1party/permissions.yaml); the development stack needs them for the smoke test and the
-- browser tests that read and register a greeting.
INSERT INTO security.permission (permission_code, module, description_en, offline_allowed, requires_mfa, scope)
VALUES
    ('hello.greeting.read',     'hello', 'Read the greetings (template module)',  false, false, 'ENTITY'),
    ('hello.greeting.register', 'hello', 'Register a greeting (template module)', false, false, 'ENTITY')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO security.app_user (user_id, home_entity_id, username, display_name, language, user_kind, status)
VALUES
    ('0190f000-0000-7000-8000-0000000000a1', '0190f000-0000-7000-8000-000000000001', 'fed-admin',   'Federation Admin',   'en', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000a2', '0190f000-0000-7000-8000-000000000001', 'fed-officer', 'Federation Officer', 'en', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000a3', '0190f000-0000-7000-8000-000000000002', 'mpcs-admin',  'MPCS Admin',         'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000a4', '0190f000-0000-7000-8000-000000000002', 'cashier',     'Shop Cashier',       'ta', 'BACK_OFFICE', 'ACTIVE')
ON CONFLICT (user_id) DO NOTHING;

-- One development role per entity, class OWN, every permission of the catalogue; and one for
-- the society's cashier with the reads alone (the codes a GET of a slice asks for, by the
-- catalogue's naming: *.view and *.read).
INSERT INTO security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)
VALUES
    ('0190f000-0000-7000-8000-0000000000b1', '0190f000-0000-7000-8000-000000000001', 'Developer (every permission)', false, 'OWN', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000b2', '0190f000-0000-7000-8000-000000000002', 'Developer (every permission)', false, 'OWN', 'ACTIVE'),
    ('0190f000-0000-7000-8000-0000000000b3', '0190f000-0000-7000-8000-000000000002', 'Developer (reads only)',       false, 'OWN', 'ACTIVE')
ON CONFLICT (role_id) DO NOTHING;

INSERT INTO security.role_permission (role_id, permission_code)
SELECT r.role_id, p.permission_code
FROM security.role r
CROSS JOIN security.permission p
WHERE r.role_id IN ('0190f000-0000-7000-8000-0000000000b1', '0190f000-0000-7000-8000-0000000000b2')
ON CONFLICT DO NOTHING;

INSERT INTO security.role_permission (role_id, permission_code)
SELECT '0190f000-0000-7000-8000-0000000000b3', p.permission_code
FROM security.permission p
WHERE p.permission_code LIKE '%.view' OR p.permission_code LIKE '%.read'
ON CONFLICT DO NOTHING;

-- Entity-wide assignments: they apply at every location of the entity (doc 19 section 3.1).
-- The cashier held the every-permission role before 27 September 2026; a stack seeded before
-- then loses that row here, so that `make seed` brings an old stack to the same state as a new one.
DELETE FROM security.user_role
WHERE user_id = '0190f000-0000-7000-8000-0000000000a4'
  AND role_id = '0190f000-0000-7000-8000-0000000000b2';

INSERT INTO security.user_role (user_id, role_id, scope_entity_id, scope_location_id)
VALUES
    ('0190f000-0000-7000-8000-0000000000a1', '0190f000-0000-7000-8000-0000000000b1', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f000-0000-7000-8000-0000000000a2', '0190f000-0000-7000-8000-0000000000b1', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f000-0000-7000-8000-0000000000a3', '0190f000-0000-7000-8000-0000000000b2', '0190f000-0000-7000-8000-000000000002', NULL),
    ('0190f000-0000-7000-8000-0000000000a4', '0190f000-0000-7000-8000-0000000000b3', '0190f000-0000-7000-8000-000000000002', NULL)
ON CONFLICT DO NOTHING;
