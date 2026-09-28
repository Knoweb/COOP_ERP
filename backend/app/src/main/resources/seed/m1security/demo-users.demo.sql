-- Demo data (DEMO-01, docs/DEMO.md): the users of the demonstration, one per job of the phase 1
-- storyline, each with a role of its own entity and the scope the job needs. The same users, with
-- the password "demo", are in the dev realm (infra/compose/realm-dev.json): the `id` there is the
-- user_id here, which is what the token's `sub` carries. Loaded by `make demo-data` as the
-- PostgreSQL superuser, after seed/m1party/demo-parties.demo.sql. Safe to run again.
--
-- The roles are narrow on purpose, unlike the development roles of users.dev.sql: the demo shows
-- which person does what, and the permission check (COOP_ERP_ENFORCE_PERMISSIONS, on in compose)
-- refuses everything else. A location-scoped assignment (scope_location_id) holds only at that
-- location: row-level security refuses the stores user a write at any other location (PR #148).
-- The permission codes are those of seed/m1party/permissions.yaml; a code the catalogue does not
-- have is skipped by the join below rather than failing the load.

INSERT INTO security.app_user (user_id, home_entity_id, username, display_name, language, user_kind, status)
VALUES
    -- the Federation (0190f000-0000-7000-8000-000000000001)
    ('0190f0de-0000-7000-8000-000000000201', '0190f000-0000-7000-8000-000000000001', 'fed-steward',  'Nirmala Perera',       'en', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000202', '0190f000-0000-7000-8000-000000000001', 'fed-pricing',  'Suresh Fernando',      'en', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000203', '0190f000-0000-7000-8000-000000000001', 'fed-stores',   'Kamal Jayasinghe',     'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000204', '0190f000-0000-7000-8000-000000000001', 'fed-sales',    'Tharindu Silva',       'en', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000205', '0190f000-0000-7000-8000-000000000001', 'fed-accounts', 'Fathima Rizwan',       'en', 'BACK_OFFICE', 'ACTIVE'),
    -- D101 Wayamba Cooperative Distributors
    ('0190f0de-0000-7000-8000-000000000211', '0190f0de-0000-7000-8000-0000000000e1', 'd101-buyer',    'Chaminda Rathnayake', 'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000212', '0190f0de-0000-7000-8000-0000000000e1', 'd101-stores',   'Lasantha Herath',     'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000213', '0190f0de-0000-7000-8000-0000000000e1', 'd101-accounts', 'Dilani Wijesekara',   'si', 'BACK_OFFICE', 'ACTIVE'),
    -- D102 Northern Cooperative Distributors
    ('0190f0de-0000-7000-8000-000000000221', '0190f0de-0000-7000-8000-0000000000e2', 'd102-buyer',    'Kumaran Sivalingam',  'ta', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000222', '0190f0de-0000-7000-8000-0000000000e2', 'd102-stores',   'Arun Thevarajah',     'ta', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000223', '0190f0de-0000-7000-8000-0000000000e2', 'd102-accounts', 'Priya Nadarajah',     'ta', 'BACK_OFFICE', 'ACTIVE'),
    -- the societies
    ('0190f0de-0000-7000-8000-000000000231', '0190f0de-0000-7000-8000-0000000000e3', 'm101-buyer',    'Sandya Kumari',       'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000232', '0190f0de-0000-7000-8000-0000000000e3', 'm101-manager',  'Ruwan Dissanayake',   'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000233', '0190f0de-0000-7000-8000-0000000000e3', 'm101-shop',     'Malani Gunawardena',  'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000242', '0190f0de-0000-7000-8000-0000000000e4', 'm102-manager',  'Nimal Bandara',       'si', 'BACK_OFFICE', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000252', '0190f0de-0000-7000-8000-0000000000e5', 'm103-manager',  'Selvaraj Yogarajah',  'ta', 'BACK_OFFICE', 'ACTIVE')
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO security.role (role_id, owner_entity_id, name_en, is_template, role_class, status)
VALUES
    ('0190f0de-0000-7000-8000-000000000301', '0190f000-0000-7000-8000-000000000001', 'Demo: catalogue steward',          false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000302', '0190f000-0000-7000-8000-000000000001', 'Demo: pricing and relationships',  false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000303', '0190f000-0000-7000-8000-000000000001', 'Demo: stores and dispatch',        false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000304', '0190f000-0000-7000-8000-000000000001', 'Demo: sales',                      false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000305', '0190f000-0000-7000-8000-000000000001', 'Demo: accounts',                   false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000306', '0190f000-0000-7000-8000-000000000001', 'Demo: administration',             false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000311', '0190f0de-0000-7000-8000-0000000000e1', 'Demo: buying, selling and prices', false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000312', '0190f0de-0000-7000-8000-0000000000e1', 'Demo: stores and receiving',       false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000313', '0190f0de-0000-7000-8000-0000000000e1', 'Demo: accounts',                   false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000321', '0190f0de-0000-7000-8000-0000000000e2', 'Demo: buying, selling and prices', false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000322', '0190f0de-0000-7000-8000-0000000000e2', 'Demo: stores and receiving',       false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000323', '0190f0de-0000-7000-8000-0000000000e2', 'Demo: accounts',                   false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000331', '0190f0de-0000-7000-8000-0000000000e3', 'Demo: society buyer',              false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000332', '0190f0de-0000-7000-8000-0000000000e3', 'Demo: society manager',            false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000333', '0190f0de-0000-7000-8000-0000000000e3', 'Demo: shop staff',                 false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000342', '0190f0de-0000-7000-8000-0000000000e4', 'Demo: society manager',            false, 'OWN', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000352', '0190f0de-0000-7000-8000-0000000000e5', 'Demo: society manager',            false, 'OWN', 'ACTIVE')
ON CONFLICT (role_id) DO NOTHING;

-- What each role may do. The same job has the same codes in every entity.
WITH job (role_id, codes) AS (
    VALUES
        -- Federation catalogue steward
        ('0190f0de-0000-7000-8000-000000000301'::uuid, ARRAY[
            'cat.sku.view', 'cat.sku.create', 'cat.sku.create_local', 'cat.sku.promote', 'cat.sku.deactivate',
            'cat.barcode.manage', 'cat.image.manage', 'cat.tag.manage', 'cat.batch.correct', 'cat.supplier.manage',
            'prc.pricelist.view', 'inv.stock.view', 'prt.location.view', 'gov.entity.view']),
        -- Federation pricing: the trade price list and the relationships that trade on it
        ('0190f0de-0000-7000-8000-000000000302'::uuid, ARRAY[
            'prc.pricelist.view', 'prc.pricelist.author', 'prc.pricelist.publish',
            'prt.relationship.view', 'prt.relationship.open', 'prt.relationship.activate', 'prt.relationship.amend',
            'cat.sku.view', 'gov.entity.view']),
        -- stores and dispatch, at the Federation warehouse only (assignment below)
        ('0190f0de-0000-7000-8000-000000000303'::uuid, ARRAY[
            'inv.stock.view', 'inv.stock.receive', 'inv.opening.prepare', 'inv.opening.sign', 'whs.pick',
            'del.note.draft', 'del.note.dispatch', 'del.pod.record', 'trd.document.view', 'cat.sku.view',
            'prt.location.view', 'gov.entity.view']),
        -- Federation sales: accepts orders, issues delivery notes
        ('0190f0de-0000-7000-8000-000000000304'::uuid, ARRAY[
            'trd.document.view', 'ord.order.accept', 'del.note.draft', 'del.note.issue', 'cat.sku.view', 'gov.entity.view', 'prt.location.view',
            'prt.relationship.view', 'prc.pricelist.view', 'inv.stock.view',
            'rpt.report.run', 'rpt.export.run']),
        -- Federation accounts: invoices, and the second signature on the opening balance
        ('0190f0de-0000-7000-8000-000000000305'::uuid, ARRAY[
            'trd.document.view', 'bil.invoice.issue', 'bil.creditnote.issue', 'bil.debitnote.issue',
            'bil.payment.record', 'bil.statement.generate', 'inv.opening.countersign', 'inv.stock.view',
            'prt.relationship.view', 'cat.sku.view', 'prt.location.view', 'gov.entity.view',
            'rpt.report.run', 'rpt.export.run']),
        -- Federation administration (phase 4, docs/demo/07): the society register (register,
        -- activate, suspend), the Federation's users and their roles, and external grants. Held
        -- by fed-steward beside the catalogue role; until 28 September 2026 no demo user held
        -- gov.entity.register, so "Register a society" was offered to nobody in the demo cast.
        ('0190f0de-0000-7000-8000-000000000306'::uuid, ARRAY[
            'gov.entity.view', 'gov.entity.register', 'gov.entity.activate', 'gov.entity.suspend',
            'gov.user.view', 'gov.user.manage', 'gov.role.manage', 'gov.external.grant', 'prt.location.view']),
        -- distributor commercial: buys from the Federation, prices and sells to its societies
        ('0190f0de-0000-7000-8000-000000000311'::uuid, ARRAY[
            'trd.document.view', 'ord.order.draft', 'ord.order.submit', 'ord.order.accept', 'del.note.draft',
            'del.note.issue', 'bil.invoice.dispute', 'cat.sku.view', 'prc.pricelist.view', 'prc.pricelist.author',
            'prc.pricelist.publish', 'prt.relationship.view', 'prt.relationship.open', 'prt.relationship.activate',
            'prt.relationship.amend', 'gov.entity.view', 'inv.stock.view', 'prt.location.view',
            'rpt.report.run', 'rpt.export.run']),
        ('0190f0de-0000-7000-8000-000000000321'::uuid, ARRAY[
            'trd.document.view', 'ord.order.draft', 'ord.order.submit', 'ord.order.accept', 'del.note.draft',
            'del.note.issue', 'bil.invoice.dispute', 'cat.sku.view', 'prc.pricelist.view', 'prc.pricelist.author',
            'prc.pricelist.publish', 'prt.relationship.view', 'prt.relationship.open', 'prt.relationship.activate',
            'prt.relationship.amend', 'gov.entity.view', 'inv.stock.view', 'prt.location.view',
            'rpt.report.run', 'rpt.export.run']),
        -- distributor stores: receives (GRN), holds the opening stock, dispatches to societies
        ('0190f0de-0000-7000-8000-000000000312'::uuid, ARRAY[
            'whs.grn.confirm', 'shop.grn.confirm', 'inv.stock.view', 'inv.stock.receive', 'inv.opening.prepare', 'inv.opening.sign',
            'whs.pick', 'del.note.draft', 'del.note.dispatch', 'trd.document.view', 'cat.sku.view', 'prt.location.view', 'gov.entity.view']),
        ('0190f0de-0000-7000-8000-000000000322'::uuid, ARRAY[
            'whs.grn.confirm', 'shop.grn.confirm', 'inv.stock.view', 'inv.stock.receive', 'inv.opening.prepare', 'inv.opening.sign',
            'whs.pick', 'del.note.draft', 'del.note.dispatch', 'trd.document.view', 'cat.sku.view', 'prt.location.view', 'gov.entity.view']),
        -- distributor accounts
        ('0190f0de-0000-7000-8000-000000000313'::uuid, ARRAY[
            'trd.document.view', 'bil.invoice.issue', 'bil.invoice.dispute', 'bil.payment.record',
            'bil.statement.generate', 'inv.opening.countersign', 'inv.stock.view', 'prt.relationship.view',
            'cat.sku.view', 'prt.location.view', 'gov.entity.view',
            'rpt.report.run', 'rpt.export.run']),
        ('0190f0de-0000-7000-8000-000000000323'::uuid, ARRAY[
            'trd.document.view', 'bil.invoice.issue', 'bil.invoice.dispute', 'bil.payment.record',
            'bil.statement.generate', 'inv.opening.countersign', 'inv.stock.view', 'prt.relationship.view',
            'cat.sku.view', 'prt.location.view', 'gov.entity.view',
            'rpt.report.run', 'rpt.export.run']),
        -- society buyer
        ('0190f0de-0000-7000-8000-000000000331'::uuid, ARRAY[
            'trd.document.view', 'ord.order.draft', 'ord.order.submit', 'whs.grn.confirm', 'shop.grn.confirm',
            'bil.invoice.dispute', 'cat.sku.view', 'prc.pricelist.view', 'prt.relationship.view', 'inv.stock.view',
            'prt.location.view', 'inv.opening.prepare', 'inv.opening.sign', 'gov.entity.view']),
        -- society manager: the second signature (countersign), the shops and their tills; receives
        -- the Hettipola shop's transfer, which has no staff user of its own (DEMO-02)
        ('0190f0de-0000-7000-8000-000000000332'::uuid, ARRAY[
            'inv.opening.prepare', 'inv.opening.sign', 'inv.opening.countersign', 'inv.adjust.approve',
            'inv.writeoff.approve', 'prt.location.view', 'prt.location.activate', 'prt.location.primary',
            'prt.position.manage', 'sys.device.view', 'gov.user.view', 'trd.document.view', 'inv.stock.view',
            'cat.sku.view', 'gov.entity.view',
            'rpt.report.run', 'rpt.export.run', 'inv.transfer.issue', 'shop.transfer.receive', 'sys.device.enrol',
            'pos.receipt.view']),
        -- the society managers of M102 and M103 also order from their distributor and receive
        -- at the shop (DEMO-02: the second tier of the demo history)
        ('0190f0de-0000-7000-8000-000000000342'::uuid, ARRAY[
            'ord.order.draft', 'ord.order.submit', 'shop.grn.confirm', 'prc.pricelist.view', 'prt.relationship.view',
            'inv.opening.prepare', 'inv.opening.sign', 'inv.opening.countersign', 'inv.adjust.approve',
            'inv.writeoff.approve', 'prt.location.view', 'prt.location.activate', 'prt.location.primary',
            'prt.position.manage', 'sys.device.view', 'gov.user.view', 'trd.document.view', 'inv.stock.view',
            'cat.sku.view', 'gov.entity.view',
            'rpt.report.run', 'rpt.export.run', 'inv.transfer.issue', 'sys.device.enrol', 'pos.receipt.view']),
        ('0190f0de-0000-7000-8000-000000000352'::uuid, ARRAY[
            'ord.order.draft', 'ord.order.submit', 'shop.grn.confirm', 'prc.pricelist.view', 'prt.relationship.view',
            'inv.opening.prepare', 'inv.opening.sign', 'inv.opening.countersign', 'inv.adjust.approve',
            'inv.writeoff.approve', 'prt.location.view', 'prt.location.activate', 'prt.location.primary',
            'prt.position.manage', 'sys.device.view', 'gov.user.view', 'trd.document.view', 'inv.stock.view',
            'cat.sku.view', 'gov.entity.view',
            'rpt.report.run', 'rpt.export.run', 'inv.transfer.issue', 'sys.device.enrol', 'pos.receipt.view']),
        -- shop staff, at one shop only
        ('0190f0de-0000-7000-8000-000000000333'::uuid, ARRAY[
            'shop.grn.confirm', 'shop.count.record', 'shop.transfer.request', 'shop.transfer.receive',
            'inv.stock.view', 'cat.sku.view', 'prt.location.view', 'pos.receipt.view', 'gov.entity.view'])
)
INSERT INTO security.role_permission (role_id, permission_code)
SELECT job.role_id, p.permission_code
FROM job
CROSS JOIN LATERAL unnest(job.codes) AS code
JOIN security.permission p ON p.permission_code = code
ON CONFLICT DO NOTHING;

-- The assignments. NULL location: entity-wide. A location: that location only.
INSERT INTO security.user_role (user_id, role_id, scope_entity_id, scope_location_id)
VALUES
    ('0190f0de-0000-7000-8000-000000000201', '0190f0de-0000-7000-8000-000000000301', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f0de-0000-7000-8000-000000000201', '0190f0de-0000-7000-8000-000000000306', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f0de-0000-7000-8000-000000000202', '0190f0de-0000-7000-8000-000000000302', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f0de-0000-7000-8000-000000000203', '0190f0de-0000-7000-8000-000000000303', '0190f000-0000-7000-8000-000000000001', '0190f0de-0000-7000-8000-000000000101'),
    ('0190f0de-0000-7000-8000-000000000204', '0190f0de-0000-7000-8000-000000000304', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f0de-0000-7000-8000-000000000205', '0190f0de-0000-7000-8000-000000000305', '0190f000-0000-7000-8000-000000000001', NULL),
    ('0190f0de-0000-7000-8000-000000000211', '0190f0de-0000-7000-8000-000000000311', '0190f0de-0000-7000-8000-0000000000e1', NULL),
    ('0190f0de-0000-7000-8000-000000000212', '0190f0de-0000-7000-8000-000000000312', '0190f0de-0000-7000-8000-0000000000e1', '0190f0de-0000-7000-8000-000000000111'),
    ('0190f0de-0000-7000-8000-000000000213', '0190f0de-0000-7000-8000-000000000313', '0190f0de-0000-7000-8000-0000000000e1', NULL),
    ('0190f0de-0000-7000-8000-000000000221', '0190f0de-0000-7000-8000-000000000321', '0190f0de-0000-7000-8000-0000000000e2', NULL),
    ('0190f0de-0000-7000-8000-000000000222', '0190f0de-0000-7000-8000-000000000322', '0190f0de-0000-7000-8000-0000000000e2', '0190f0de-0000-7000-8000-000000000121'),
    ('0190f0de-0000-7000-8000-000000000223', '0190f0de-0000-7000-8000-000000000323', '0190f0de-0000-7000-8000-0000000000e2', NULL),
    ('0190f0de-0000-7000-8000-000000000231', '0190f0de-0000-7000-8000-000000000331', '0190f0de-0000-7000-8000-0000000000e3', NULL),
    ('0190f0de-0000-7000-8000-000000000232', '0190f0de-0000-7000-8000-000000000332', '0190f0de-0000-7000-8000-0000000000e3', NULL),
    ('0190f0de-0000-7000-8000-000000000233', '0190f0de-0000-7000-8000-000000000333', '0190f0de-0000-7000-8000-0000000000e3', '0190f0de-0000-7000-8000-000000000132'),
    ('0190f0de-0000-7000-8000-000000000242', '0190f0de-0000-7000-8000-000000000342', '0190f0de-0000-7000-8000-0000000000e4', NULL),
    ('0190f0de-0000-7000-8000-000000000252', '0190f0de-0000-7000-8000-000000000352', '0190f0de-0000-7000-8000-0000000000e5', NULL)
ON CONFLICT DO NOTHING;
