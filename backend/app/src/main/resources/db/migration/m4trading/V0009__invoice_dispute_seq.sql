-- An invoice's dispute acts are ordered by a sequence, not by a clock (wave 2, M4MONEY-11;
-- docs/progress/deviations/2026-10-06-wave2-payments-and-cheques.md (4); CR-24A-3 item 6).
-- recorded_at is the writing instance's wall clock, so with two instances whose clocks differ a
-- RESOLVED can sort before the DISPUTED it resolves (AGENTS.md: sequence numbers, not clocks,
-- order events). The identity is filled for the rows already there when the column is added; on
-- an insert-only table that is their insert order. The inserts name no seq, and an identity's
-- sequence needs no grant to app_rw. recorded_at stays, for display.
ALTER TABLE trading.invoice_dispute ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY;
CREATE INDEX invoice_dispute_invoice_seq ON trading.invoice_dispute (invoice_document_id, seq);
