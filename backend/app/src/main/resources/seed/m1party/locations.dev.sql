-- Development seed for M1-05: two locations of the development MPCS
-- (0190f000-0000-7000-8000-000000000002, entities.dev.sql), so the shop list has rows and a
-- developer has a shop to walk through its life cycle. Loaded by `make seed` as the PostgreSQL
-- superuser, after entities.dev.sql; safe to run again.
--
--   0190f000-0000-7000-8000-000000000101  S01, a shop, PLANNED
--   0190f000-0000-7000-8000-000000000102  W01, a warehouse, ACTIVE
--
-- No till positions on purpose. A position's numbering series (RCT, CPR) live in
-- kernel.numbering_series, which a seed of M1 may not write (tools/check-schema-ownership.mjs),
-- and a position without its series would issue nothing. Register positions through the API
-- (POST /v1/party/locations/{locationId}/positions) and the series come with them; naming the
-- primary till (PUT .../primary-till) registers the shop's location series the seed also lacks.

INSERT INTO party.location (
    location_id, owner_entity_id, location_code, location_type, name_en, name_si, name_ta,
    district, language, trading_hours, size_band, connectivity_spec_met, status
)
VALUES
    ('0190f000-0000-7000-8000-000000000101', '0190f000-0000-7000-8000-000000000002', 'S01', 'SHOP',
     'Development shop', 'සංවර්ධන සාප්පුව', 'மேம்பாட்டுக் கடை',
     'Gampaha', 'si',
     '[{"day":"MON","opens":"08:00","closes":"20:00"},{"day":"TUE","opens":"08:00","closes":"20:00"},'
     '{"day":"WED","opens":"08:00","closes":"20:00"},{"day":"THU","opens":"08:00","closes":"20:00"},'
     '{"day":"FRI","opens":"08:00","closes":"20:00"},{"day":"SAT","opens":"08:00","closes":"14:00"}]',
     'S', false, 'PLANNED'),
    ('0190f000-0000-7000-8000-000000000102', '0190f000-0000-7000-8000-000000000002', 'W01', 'WAREHOUSE',
     'Development warehouse', 'සංවර්ධන ගබඩාව', 'மேம்பாட்டுக் கிடங்கு',
     'Gampaha', 'si', NULL, 'M', true, 'ACTIVE')
ON CONFLICT (location_id) DO NOTHING;
