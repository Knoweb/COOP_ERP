-- Development seed for trading relationships (M1-04): the chain of doc 21 flow 6.2 on the dev
-- entities of entities.dev.sql, which runs first (make seed takes the files in name order).
-- Loaded by `make seed` as the PostgreSQL superuser. Safe to run again: a row that exists, or
-- that would overlap an ACTIVE row of the same pair, is left alone.
--
--   Federation (FED) -> Development Distributors (D001)   ACTIVE, the Federation's uniform list
--   Development Distributors (D001) -> Development MPCS (M001)   ACTIVE, Rs 5,000,000, 30 days
--
-- The price list ids name no row: M3 has no TRADE price lists yet, and the relationship's
-- price_list_id has no foreign key (21A section 3.1). When M3 seeds its lists, it uses these
-- two ids.

INSERT INTO party.entity_relationship (
    relationship_id, seller_entity_id, buyer_entity_id, price_list_id, credit_limit,
    payment_terms_days, discrepancy_window_days, order_lock_hours_before_eta, allocation_rule,
    status, effective_from
)
VALUES
    ('0190f000-0000-7000-8000-000000000401',
     '0190f000-0000-7000-8000-000000000001', '0190f000-0000-7000-8000-000000000003',
     '0190f000-0000-7000-8000-000000000501', 25000000.00,
     45, 7, 24, 'FCFS', 'ACTIVE', DATE '2026-01-01'),
    ('0190f000-0000-7000-8000-000000000402',
     '0190f000-0000-7000-8000-000000000003', '0190f000-0000-7000-8000-000000000002',
     '0190f000-0000-7000-8000-000000000502', 5000000.00,
     30, 7, 24, 'FCFS', 'ACTIVE', DATE '2026-01-01')
ON CONFLICT DO NOTHING;
