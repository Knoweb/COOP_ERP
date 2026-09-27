-- Decisions of 27 September 2026 on the architect's delegation (CR-19A-8). Lane C.
--
-- 1. What a notification retry needs is sealed, never held in clear.
--    V0059 held the recipient (a phone number) and the placeholders (for M1's direct send, a
--    temporary password) in clear for up to 24 hours, against doc 19 section 7 ("phone numbers
--    are hashed in the log"). The application now seals both with AES-256-GCM under a key the
--    database never sees (coop-erp.notification.pending-key; kernel PendingSeal) and stores the
--    ciphertext and the id of the key. The two clear columns are dropped. A row still QUEUED when
--    this runs has nothing to open and is failed by the next sweep ("nothing held for the
--    retry"), which a notification at most 24 hours old can afford. DROP COLUMN does not rewrite
--    the table: the old values stay in the dead space of the pages until PostgreSQL reuses it.
--    That is a developer database today; a deployed one never held them.
--
-- 2. A FAILED upload's object is deleted after a retention, and the row says when.
--    kernel.document_attachment (V0050, V0058) and kernel.object_upload (V0060) never change once
--    settled. The clean-up job (AttachmentCleanupJob, K-12) deletes the object of a FAILED row
--    after attachment.failed_retention_days and records object_deleted_at, the one change a
--    settled row admits, on a FAILED row only, once. A COMPLETE or VERIFIED object is never
--    deleted by the kernel: it is evidence or a module's image. document_attachment also gains
--    settled_at (object_upload has it since V0060), set by the verifier, from which the
--    retention counts; a row settled before this migration counts from its upload window.

-- ---- 1. notification_pending -----------------------------------------------------------------

ALTER TABLE kernel.notification_pending
    DROP COLUMN recipient,
    DROP COLUMN arguments,
    ADD COLUMN sealed bytea,
    ADD COLUMN key_id varchar(16);

COMMENT ON COLUMN kernel.notification_pending.sealed IS
    'AES-256-GCM of {recipient, arguments}: a 12-byte nonce, then the ciphertext; the notification id is the associated data. Null once cleared.';
COMMENT ON COLUMN kernel.notification_pending.key_id IS
    'Which configured key sealed the row (a hash prefix of the key, never the key). Null once cleared.';

GRANT UPDATE (sealed, key_id) ON kernel.notification_pending TO app_rw;

-- ---- 2. document_attachment ------------------------------------------------------------------

ALTER TABLE kernel.document_attachment
    ADD COLUMN settled_at timestamptz,
    ADD COLUMN object_deleted_at timestamptz;

CREATE OR REPLACE FUNCTION kernel.document_attachment_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'PENDING' THEN
        IF NEW.object_deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'attachment.object_not_deletable' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    -- A settled row changes once more at most: a FAILED row's object is deleted.
    IF OLD.status = 'FAILED'
       AND OLD.object_deleted_at IS NULL
       AND NEW.object_deleted_at IS NOT NULL
       AND (to_jsonb(NEW) - 'object_deleted_at') = (to_jsonb(OLD) - 'object_deleted_at') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'attachment.immutable' USING ERRCODE = 'check_violation';
END
$$;

GRANT UPDATE (settled_at, object_deleted_at) ON kernel.document_attachment TO app_rw;

CREATE INDEX document_attachment_failed_idx
    ON kernel.document_attachment (settled_at)
    WHERE status = 'FAILED' AND object_deleted_at IS NULL;

-- ---- 2. object_upload ------------------------------------------------------------------------

ALTER TABLE kernel.object_upload
    ADD COLUMN object_deleted_at timestamptz;

CREATE OR REPLACE FUNCTION kernel.object_upload_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'PENDING' THEN
        -- A settled row changes once more at most: a FAILED row's object is deleted.
        IF OLD.status = 'FAILED'
           AND OLD.object_deleted_at IS NULL
           AND NEW.object_deleted_at IS NOT NULL
           AND (to_jsonb(NEW) - 'object_deleted_at') = (to_jsonb(OLD) - 'object_deleted_at') THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'object.immutable' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.object_deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'object.not_deletable' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.object_key IS DISTINCT FROM OLD.object_key
       OR NEW.owner_module IS DISTINCT FROM OLD.owner_module
       OR NEW.owner_entity_id IS DISTINCT FROM OLD.owner_entity_id
       OR NEW.content_type IS DISTINCT FROM OLD.content_type
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'object.identity_immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

GRANT UPDATE (object_deleted_at) ON kernel.object_upload TO app_rw;

CREATE INDEX object_upload_failed_idx
    ON kernel.object_upload (settled_at)
    WHERE status = 'FAILED' AND object_deleted_at IS NULL;
