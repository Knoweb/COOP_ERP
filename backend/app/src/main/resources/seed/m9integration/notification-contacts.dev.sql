-- Development and demo contacts of the notification rules (M9, integration.notification_contact):
-- who at an entity is reached for the role code of a rule's audience. Loaded by `make seed` (and so
-- by `make demo-data`, which runs it first) as the PostgreSQL superuser. Safe to run again.
--
-- Every address is on the reserved .test domain and every number is a placeholder: the local
-- stack sends e-mail to Mailpit (http://localhost:8025) and SMS to the log provider only, and
-- nothing leaves the machine. The language is the one the entity works in, so the demo's mails
-- arrive in Sinhala, Tamil and English.
--
--   0190f000-0000-7000-8000-000000000001  the Federation (entities.dev.sql)
--   0190f000-0000-7000-8000-000000000002  the development MPCS (entities.dev.sql)
--   0190f0de-0000-7000-8000-0000000000e1  D101 Wayamba Cooperative Distributors (demo-parties.demo.sql)
--   0190f0de-0000-7000-8000-0000000000e2  D102 Northern Cooperative Distributors
--   0190f0de-0000-7000-8000-0000000000e3  M101 Kuliyapitiya MPCS
--   0190f0de-0000-7000-8000-0000000000e4  M102 Pannala MPCS
--   0190f0de-0000-7000-8000-0000000000e5  M103 Point Pedro MPCS

INSERT INTO integration.notification_contact (contact_id, owner_entity_id, role_code, channel, address, language)
VALUES
    ('0190f9a1-0000-7000-8000-000000000001', '0190f000-0000-7000-8000-000000000001', 'ACCOUNTS', 'EMAIL', 'accounts@federation.coop-erp.test', 'en'),
    ('0190f9a1-0000-7000-8000-000000000002', '0190f000-0000-7000-8000-000000000002', 'ACCOUNTS', 'EMAIL', 'accounts@mpcs.coop-erp.test', 'si'),
    ('0190f9a1-0000-7000-8000-000000000003', '0190f000-0000-7000-8000-000000000002', 'MANAGER', 'EMAIL', 'manager@mpcs.coop-erp.test', 'si'),
    ('0190f9a1-0000-7000-8000-000000000011', '0190f0de-0000-7000-8000-0000000000e1', 'ACCOUNTS', 'EMAIL', 'accounts@d101.coop-erp.test', 'si'),
    ('0190f9a1-0000-7000-8000-000000000012', '0190f0de-0000-7000-8000-0000000000e1', 'ACCOUNTS', 'SMS', '0700000101', 'si'),
    ('0190f9a1-0000-7000-8000-000000000021', '0190f0de-0000-7000-8000-0000000000e2', 'ACCOUNTS', 'EMAIL', 'accounts@d102.coop-erp.test', 'ta'),
    ('0190f9a1-0000-7000-8000-000000000022', '0190f0de-0000-7000-8000-0000000000e2', 'ACCOUNTS', 'SMS', '0700000102', 'ta'),
    ('0190f9a1-0000-7000-8000-000000000031', '0190f0de-0000-7000-8000-0000000000e3', 'ACCOUNTS', 'EMAIL', 'accounts@m101.coop-erp.test', 'si'),
    ('0190f9a1-0000-7000-8000-000000000032', '0190f0de-0000-7000-8000-0000000000e3', 'MANAGER', 'EMAIL', 'manager@m101.coop-erp.test', 'si'),
    ('0190f9a1-0000-7000-8000-000000000041', '0190f0de-0000-7000-8000-0000000000e4', 'ACCOUNTS', 'EMAIL', 'accounts@m102.coop-erp.test', 'si'),
    ('0190f9a1-0000-7000-8000-000000000042', '0190f0de-0000-7000-8000-0000000000e4', 'MANAGER', 'EMAIL', 'manager@m102.coop-erp.test', 'si'),
    ('0190f9a1-0000-7000-8000-000000000051', '0190f0de-0000-7000-8000-0000000000e5', 'ACCOUNTS', 'EMAIL', 'accounts@m103.coop-erp.test', 'ta'),
    ('0190f9a1-0000-7000-8000-000000000052', '0190f0de-0000-7000-8000-0000000000e5', 'MANAGER', 'EMAIL', 'manager@m103.coop-erp.test', 'ta')
ON CONFLICT (contact_id) DO NOTHING;
