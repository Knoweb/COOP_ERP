-- Wave 2, PR 10 (M5-13; decided 6 October 2026: docs/progress/deviations/2026-10-06-wave2-stock-movements.md
-- (1); CR-25A-1 item 4): each count line carries the moment its lot was counted, which the web form
-- stamps as the line is entered. The line's book is then the lot as it stood at that moment, its
-- quantity less the movements that occurred after (by occurred_at, so a till's sale uploaded late
-- falls on the right side), and selling the item between counting the shelf and pressing submit is
-- no longer a surplus. Nullable: a line sent without it (an older client, the demo loader) keeps
-- the rule of submit time. Insert-only like the rest of the line: the table-level INSERT grant and
-- the row-level policies of V0005 cover the new column.
ALTER TABLE inventory.count_line ADD COLUMN counted_at timestamptz;

COMMENT ON COLUMN inventory.count_line.counted_at IS
    'When the lot was counted (the form stamps each line); null: measured against the book at submit.';
COMMENT ON COLUMN inventory.count_line.expected_qty IS
    'The book the line is measured against: the lot at counted_at when given, else the lot at submit (E-06).';
