-- Compensating rollback for security.user_has_permission introduced by PR #74.
--
-- This belongs to m1security because Rule R4 forbids a kernel migration
-- from modifying the security schema.

DO $rollback$
BEGIN
    IF to_regprocedure(
        'security.user_has_permission(uuid,uuid,uuid,text)'
    ) IS NOT NULL THEN
        EXECUTE
            'DROP FUNCTION security.user_has_permission(uuid,uuid,uuid,text)';
    END IF;
END
$rollback$;