-- Compensating rollback for the kernel tables introduced by PR #74.
--
-- The bad creating migrations are removed from the current migration set,
-- but databases that already ran PR #74 may still contain these objects.
-- Therefore every drop is conditional.

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