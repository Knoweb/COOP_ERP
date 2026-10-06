-- Wave 2 of the code review (M7CR-04), decided 6 October 2026 on the architect's delegation:
-- docs/progress/deviations/2026-10-06-wave2-m7-credit-book.md (1), CR-27A-1 item 1. The account
-- state machine gains REOPEN: CLOSED -> SUSPENDED, so a till fact that landed on a closed account
-- can be settled at the office (reopen, settle or reverse, close again). The history row of a
-- reopening is REOPENED; V0002's CHECK listed four actions and is never edited, so it is
-- recreated here. No other change: the credits that settle charges (M7CR-08) need no table.
ALTER TABLE customers.account_history DROP CONSTRAINT account_history_action_check;
ALTER TABLE customers.account_history
    ADD CONSTRAINT account_history_action_check
    CHECK (action IN ('LIMITS_AMENDED', 'SUSPENDED', 'REINSTATED', 'CLOSED', 'REOPENED'));
