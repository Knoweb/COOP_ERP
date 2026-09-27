-- Development seed for the pricing module (23A section 3.1): the two TRADE lists that the
-- development relationships of seed/m1party/relationships.dev.sql bind, published so that the
-- relationships' price-list check (M1's TradePriceListCheck, answered by M3) accepts them.
-- Loaded by `make seed` as the PostgreSQL superuser, after the M1 seeds (name order of the
-- module folders). Safe to run again: existing rows are left alone.
--
--   0190f000-0000-7000-8000-000000000501  the Federation's uniform list (Federation -> D001)
--   0190f000-0000-7000-8000-000000000502  Development Distributors' list for Development MPCS
--
-- No lines: the development catalogue has no SKUs yet; add them through the screen or the API
-- (a new version: POST /v1/pricing/lists/{id}/versions, PUT .../lines, POST .../publish).

INSERT INTO pricing.price_list (
    price_list_id, owner_entity_id, kind, name, version, root_price_list_id, status, apply_from, published_at
)
VALUES
    ('0190f000-0000-7000-8000-000000000501', '0190f000-0000-7000-8000-000000000001',
     'TRADE', 'Federation uniform trade list', 1, '0190f000-0000-7000-8000-000000000501',
     'PUBLISHED', DATE '2026-01-01', TIMESTAMPTZ '2026-01-01 00:00:00+05:30'),
    ('0190f000-0000-7000-8000-000000000502', '0190f000-0000-7000-8000-000000000003',
     'TRADE', 'D001 trade list for M001', 1, '0190f000-0000-7000-8000-000000000502',
     'PUBLISHED', DATE '2026-01-01', TIMESTAMPTZ '2026-01-01 00:00:00+05:30')
ON CONFLICT (price_list_id) DO NOTHING;
