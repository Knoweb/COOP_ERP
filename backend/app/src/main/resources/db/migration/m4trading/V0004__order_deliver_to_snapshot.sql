-- fix/demo-display-polish (28 September 2026; CR-24A-2 as revised): the seller's order card and
-- delivery note showed the buyer's delivery location as a short id ("000111"). The seller cannot
-- read the buyer's locations (party.location, own_read), and that stays so: a trading partner
-- has no business reading every shop and warehouse of its buyer.
--
-- The order carries, beside the id (V0002), what the buyer named: the location's code, its name
-- in the three languages and its address, copied in the buyer's own session when the order is
-- drafted (CreateOrderHandler). It is a snapshot, like a price on a line: the delivery point the
-- order was placed for, read by both sides, never changed after (the table's INSERT grant of
-- V0001 covers the new columns; nothing updates them). A renamed warehouse does not rewrite the
-- orders placed before. An order drafted without a delivery location has none of them.
ALTER TABLE trading.doc_order
    ADD COLUMN deliver_to_code varchar(12),
    ADD COLUMN deliver_to_name_en text,
    ADD COLUMN deliver_to_name_si text,
    ADD COLUMN deliver_to_name_ta text,
    ADD COLUMN deliver_to_address text;
