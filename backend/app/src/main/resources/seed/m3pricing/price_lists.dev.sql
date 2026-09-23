-- Development seed for the pricing module (17A section 10: "the pricing module's sample rows").
-- Loaded by `make seed`, which runs it as the PostgreSQL superuser: seeds are the one place
-- where rows for several entities are written at once, which row-level security forbids
-- to the application user. Safe to run again: existing rows are left alone.
--
-- The two owner ids are the development entities of infra/compose/realm-dev.json:
--   0190f000-0000-7000-8000-000000000001  Federation  (user fed-admin)
--   0190f000-0000-7000-8000-000000000002  an MPCS     (users mpcs-admin and cashier)

INSERT INTO pricing.price_list (id, owner_entity_id, text_en, text_si, text_ta, status)
VALUES
    ('0190f000-0000-7000-8000-0000000a0001',
     '0190f000-0000-7000-8000-000000000001',
     'Welcome from the Federation', 'සම්මේලනයෙන් සාදරයෙන් පිළිගනිමු', 'கூட்டமைப்பிலிருந்து வரவேற்கிறோம்',
     'REGISTERED'),
    ('0190f000-0000-7000-8000-0000000a0002',
     '0190f000-0000-7000-8000-000000000002',
     'Welcome from the society', 'සමිතියෙන් සාදරයෙන් පිළිගනිමු', 'சங்கத்திலிருந்து வரவேற்கிறோம்',
     'REGISTERED'),
    -- No Sinhala or Tamil text on purpose: this row exercises the EN fallback tag.
    ('0190f000-0000-7000-8000-0000000a0003',
     '0190f000-0000-7000-8000-000000000002',
     'English only price list', NULL, NULL,
     'REGISTERED')
ON CONFLICT (id) DO NOTHING;
