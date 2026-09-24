-- Compensating rollback for the kernel tables introduced by PR #74.
--
-- The creating migrations (V0003, V0004) were removed from the tree by the rollback, which
-- the rule "a merged migration is never edited or deleted" forbids; it was accepted this
-- once because nothing is deployed and every developer database is recreated (make reset).
-- Every drop is conditional so that a fresh database and one that ran #74 both pass.
-- Numbered V0006, below the lanes reserved in README.md: a rollback belongs to the baseline
-- space, not to a lane, and must not raise the kernel's high-water mark above V0010.

DO $rollback$
BEGIN
    IF to_regclass('kernel.outbox_event') IS NOT NULL THEN
        EXECUTE 'DROP TABLE kernel.outbox_event';
    END IF;

    IF to_regclass('kernel.audit_log') IS NOT NULL THEN
        EXECUTE 'DROP TABLE kernel.audit_log';
    END IF;

    IF to_regclass('kernel.idempotency_key') IS NOT NULL THEN
        EXECUTE 'DROP TABLE kernel.idempotency_key';
    END IF;
END
$rollback$;