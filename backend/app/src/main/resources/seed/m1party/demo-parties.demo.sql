-- Demo data (DEMO-01, docs/DEMO.md): the parties of the demonstration to cooperative staff, in
-- a Sri Lankan setting. Loaded by `make demo-data` as the PostgreSQL superuser, after `make seed`
-- (the Federation row, 0190f000-0000-7000-8000-000000000001, comes from entities.dev.sql). Not a
-- .dev.sql file on purpose: `make seed` does not load it, so the development stack stays as small
-- as the browser tests expect. Safe to run again: existing rows are left alone.
--
-- Why rows and not commands: the identity provider's token carries the user's home entity (the
-- `ent` attribute of infra/compose/realm-dev.json), so the demo entities need ids fixed in advance,
-- and RegisterEntity mints its own. The locations follow, because the users' location-scoped
-- roles (seed/m1security/demo-users.demo.sql) name them. This is the precedent of the development
-- seed (entities.dev.sql, locations.dev.sql). Everything with a business life (till positions and
-- their numbering series, relationships, catalogue, price lists, opening stock) is made by the
-- demo loader (lk.coopfed.knoweb.demo) through the command handlers.
--
--   entity  0190f0de-0000-7000-8000-0000000000e1  D101  Wayamba Cooperative Distributors (Kurunegala)
--           0190f0de-0000-7000-8000-0000000000e2  D102  Northern Cooperative Distributors (Jaffna)
--           0190f0de-0000-7000-8000-0000000000e3  M101  Kuliyapitiya MPCS   buys from D101
--           0190f0de-0000-7000-8000-0000000000e4  M102  Pannala MPCS        buys from D101
--           0190f0de-0000-7000-8000-0000000000e5  M103  Point Pedro MPCS    buys from D102

INSERT INTO party.entity (
    entity_id, entity_code, entity_type, legal_name_en, legal_name_si, legal_name_ta,
    registration_no, vat_registration_no, district, financial_year_start_month, default_language, status
)
VALUES
    ('0190f0de-0000-7000-8000-0000000000e1', 'D101', 'DISTRIBUTOR',
     'Wayamba Cooperative Distributors', 'වයඹ සමුපකාර බෙදාහරින්නෝ', 'வயம்ப கூட்டுறவு விநியோகஸ்தர்கள்',
     'REG-D101', 'VAT-D101', 'Kurunegala', 1, 'si', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-0000000000e2', 'D102', 'DISTRIBUTOR',
     'Northern Cooperative Distributors', 'උතුරු සමුපකාර බෙදාහරින්නෝ', 'வட மாகாண கூட்டுறவு விநியோகஸ்தர்கள்',
     'REG-D102', 'VAT-D102', 'Jaffna', 1, 'ta', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-0000000000e3', 'M101', 'MPCS',
     'Kuliyapitiya MPCS', 'කුලියාපිටිය විවිධ සේවා සමුපකාර සමිතිය', 'குளியாப்பிட்டி பலநோக்கு கூட்டுறவுச் சங்கம்',
     'REG-M101', 'VAT-M101', 'Kurunegala', 1, 'si', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-0000000000e4', 'M102', 'MPCS',
     'Pannala MPCS', 'පන්නල විවිධ සේවා සමුපකාර සමිතිය', 'பன்னல பலநோக்கு கூட்டுறவுச் சங்கம்',
     'REG-M102', 'VAT-M102', 'Kurunegala', 1, 'si', 'ACTIVE'),
    ('0190f0de-0000-7000-8000-0000000000e5', 'M103', 'MPCS',
     'Point Pedro MPCS', 'පේදුරුතුඩුව විවිධ සේවා සමුපකාර සමිතිය', 'பருத்தித்துறை பலநோக்கு கூட்டுறவுச் சங்கம்',
     'REG-M103', 'VAT-M103', 'Jaffna', 1, 'ta', 'ACTIVE')
ON CONFLICT (entity_id) DO NOTHING;

--   location 0190f0de-0000-7000-8000-000000000101  FW01  Federation central warehouse (Federation)
--            0190f0de-0000-7000-8000-000000000111  W01   D101 Kurunegala warehouse
--            0190f0de-0000-7000-8000-000000000121  W01   D102 Jaffna warehouse
--            0190f0de-0000-7000-8000-000000000131  W01   M101 Kuliyapitiya stores
--            0190f0de-0000-7000-8000-000000000132  S01   M101 Kuliyapitiya town shop
--            0190f0de-0000-7000-8000-000000000133  S02   M101 Hettipola shop
--            0190f0de-0000-7000-8000-000000000142  S01   M102 Pannala shop
--            0190f0de-0000-7000-8000-000000000152  S01   M103 Point Pedro shop
-- The shops are ACTIVE, so that the loader can give each its till positions and primary till.

INSERT INTO party.location (
    location_id, owner_entity_id, location_code, location_type, name_en, name_si, name_ta,
    district, language, trading_hours, size_band, connectivity_spec_met, status
)
VALUES
    ('0190f0de-0000-7000-8000-000000000101', '0190f000-0000-7000-8000-000000000001', 'FW01', 'WAREHOUSE',
     'Federation central warehouse, Peliyagoda', 'සම්මේලන මධ්‍යම ගබඩාව, පෑලියගොඩ', 'கூட்டமைப்பு மத்திய களஞ்சியம், பேலியகொடை',
     'Gampaha', 'en', NULL, 'L', true, 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000111', '0190f0de-0000-7000-8000-0000000000e1', 'W01', 'WAREHOUSE',
     'Kurunegala warehouse', 'කුරුණෑගල ගබඩාව', 'குருநாகல் களஞ்சியம்',
     'Kurunegala', 'si', NULL, 'L', true, 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000121', '0190f0de-0000-7000-8000-0000000000e2', 'W01', 'WAREHOUSE',
     'Jaffna warehouse', 'යාපනය ගබඩාව', 'யாழ்ப்பாணம் களஞ்சியம்',
     'Jaffna', 'ta', NULL, 'L', true, 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000131', '0190f0de-0000-7000-8000-0000000000e3', 'W01', 'WAREHOUSE',
     'Kuliyapitiya stores', 'කුලියාපිටිය ගබඩාව', 'குளியாப்பிட்டி களஞ்சியம்',
     'Kurunegala', 'si', NULL, 'M', true, 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000132', '0190f0de-0000-7000-8000-0000000000e3', 'S01', 'SHOP',
     'Kuliyapitiya town shop', 'කුලියාපිටිය නගර සාප්පුව', 'குளியாப்பிட்டி நகரக் கடை',
     'Kurunegala', 'si',
     '[{"day":"MON","opens":"08:00","closes":"20:00"},{"day":"TUE","opens":"08:00","closes":"20:00"},{"day":"WED","opens":"08:00","closes":"20:00"},{"day":"THU","opens":"08:00","closes":"20:00"},{"day":"FRI","opens":"08:00","closes":"20:00"},{"day":"SAT","opens":"08:00","closes":"14:00"}]',
     'M', true, 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000133', '0190f0de-0000-7000-8000-0000000000e3', 'S02', 'SHOP',
     'Hettipola shop', 'හෙට්ටිපොළ සාප්පුව', 'ஹெட்டிபொல கடை',
     'Kurunegala', 'si',
     '[{"day":"MON","opens":"08:00","closes":"19:00"},{"day":"TUE","opens":"08:00","closes":"19:00"},{"day":"WED","opens":"08:00","closes":"19:00"},{"day":"THU","opens":"08:00","closes":"19:00"},{"day":"FRI","opens":"08:00","closes":"19:00"},{"day":"SAT","opens":"08:00","closes":"13:00"}]',
     'S', true, 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000142', '0190f0de-0000-7000-8000-0000000000e4', 'S01', 'SHOP',
     'Pannala shop', 'පන්නල සාප්පුව', 'பன்னல கடை',
     'Kurunegala', 'si',
     '[{"day":"MON","opens":"08:00","closes":"19:00"},{"day":"TUE","opens":"08:00","closes":"19:00"},{"day":"WED","opens":"08:00","closes":"19:00"},{"day":"THU","opens":"08:00","closes":"19:00"},{"day":"FRI","opens":"08:00","closes":"19:00"},{"day":"SAT","opens":"08:00","closes":"13:00"}]',
     'S', true, 'ACTIVE'),
    ('0190f0de-0000-7000-8000-000000000152', '0190f0de-0000-7000-8000-0000000000e5', 'S01', 'SHOP',
     'Point Pedro shop', 'පේදුරුතුඩුව සාප්පුව', 'பருத்தித்துறை கடை',
     'Jaffna', 'ta',
     '[{"day":"MON","opens":"07:30","closes":"19:30"},{"day":"TUE","opens":"07:30","closes":"19:30"},{"day":"WED","opens":"07:30","closes":"19:30"},{"day":"THU","opens":"07:30","closes":"19:30"},{"day":"FRI","opens":"07:30","closes":"19:30"},{"day":"SAT","opens":"07:30","closes":"13:00"}]',
     'S', true, 'ACTIVE')
ON CONFLICT (location_id) DO NOTHING;

-- M7, the privacy requests (29 September 2026, branch feat/m7-limits-privacy-snapshot): the
-- manager of Kuliyapitiya MPCS (m101-manager in seed/m1security/demo-users.demo.sql) is its
-- responsible officer (doc 21 CR-21-1; doc 27 section 3.2), who answers members' data requests.
-- Only where none is appointed, so an officer appointed through the screens is kept.
UPDATE party.entity
   SET responsible_officer_user_id = '0190f0de-0000-7000-8000-000000000232'
 WHERE entity_id = '0190f0de-0000-7000-8000-0000000000e3'
   AND responsible_officer_user_id IS NULL;
