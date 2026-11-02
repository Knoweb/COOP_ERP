-- V0020__opening_balance_rejection.sql: Controlled higher-officer rejection of draft or signed opening balances (PGL-M2-06).
-- Allows an authorized officer to reject a draft or signed opening balance during physical recount or review,
-- recording the reason and officer identity while preserving immutable row history and freeing the location for re-preparation.

ALTER TABLE inventory.opening_balance
    DROP CONSTRAINT IF EXISTS opening_balance_status_check;

ALTER TABLE inventory.opening_balance
    ADD CONSTRAINT opening_balance_status_check
    CHECK (status IN ('DRAFT', 'SIGNED_ENTITY', 'POSTED', 'REJECTED'));

ALTER TABLE inventory.opening_balance
    ADD COLUMN IF NOT EXISTS rejected_by uuid,
    ADD COLUMN IF NOT EXISTS rejected_at timestamptz,
    ADD COLUMN IF NOT EXISTS rejection_reason text;

GRANT UPDATE (status, rejected_by, rejected_at, rejection_reason) ON inventory.opening_balance TO app_rw;

DROP INDEX IF EXISTS inventory.opening_balance_open_per_location;

CREATE UNIQUE INDEX opening_balance_open_per_location ON inventory.opening_balance (location_id)
    WHERE status IN ('DRAFT', 'SIGNED_ENTITY');

