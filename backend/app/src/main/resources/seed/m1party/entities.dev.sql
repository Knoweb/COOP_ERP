-- Development seed for the party module: the two entities every other dev seed and the dev
-- realm (infra/compose/realm-dev.json) already assume exist. Loaded by `make seed`, which runs
-- it as the PostgreSQL superuser. Safe to run again: existing rows are left alone.
--
--   0190f000-0000-7000-8000-000000000001  the Federation  (users fed-admin, fed-officer)
--   0190f000-0000-7000-8000-000000000002  an MPCS         (users mpcs-admin and cashier)
--
-- The Federation row is what lets the society register work on a developer machine: every
-- lifecycle command (register, activate, suspend, reinstate) is the Federation's to give, and
-- the guard looks the caller up in this table (FederationCaller). The MPCS is ACTIVE so that
-- the register has one society to show and one card with a Suspend button.

INSERT INTO party.entity (
    entity_id, entity_code, entity_type, legal_name_en, legal_name_si, legal_name_ta,
    registration_no, vat_registration_no, district, financial_year_start_month, default_language, status
)
VALUES
    ('0190f000-0000-7000-8000-000000000001', 'FED', 'FEDERATION',
     'Cooperative Federation (development)', 'සමුපකාර සම්මේලනය (සංවර්ධන)', 'கூட்டுறவு கூட்டமைப்பு (மேம்பாடு)',
     'REG-FED-DEV', 'VAT-FED-DEV', 'Colombo', 1, 'en', 'ACTIVE'),
    ('0190f000-0000-7000-8000-000000000002', 'M001', 'MPCS',
     'Development MPCS', 'සංවර්ධන විවිධ සේවා සමුපකාර සමිතිය', 'மேம்பாட்டு பல்நோக்கு கூட்டுறவுச் சங்கம்',
     'REG-M001', 'VAT-M001', 'Gampaha', 1, 'si', 'ACTIVE')
ON CONFLICT (entity_id) DO NOTHING;
