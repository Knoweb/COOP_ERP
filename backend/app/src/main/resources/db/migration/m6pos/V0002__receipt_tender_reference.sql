-- TILL-SELL-04: Card, Wallet and QR Tenders
-- Adds reference to pos.receipt_tender to store terminal approval / transaction reference.

ALTER TABLE pos.receipt_tender ADD COLUMN reference text;
