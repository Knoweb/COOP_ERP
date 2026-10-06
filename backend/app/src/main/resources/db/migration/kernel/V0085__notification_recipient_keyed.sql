-- Wave 2 code review, PR 04 (decided 6 October 2026 on the architect's delegation:
-- docs/progress/deviations/2026-10-06-wave2-keyed-hashes.md (3), (5), (6) and
-- 2026-10-06-wave2-notifications.md (1); CR-19A-12). Findings TWK-20, M9-07, M9-08.
--
-- 1. The recipient hash is keyed. Until now recipient_hash was a plain SHA-256 of
--    "channel:recipient": the Sri Lankan mobile space (10^8 numbers) reverses it in seconds,
--    so the log, a backup or the log screen gave the numbers back. The application now writes
--    HMAC-SHA-256 under coop-erp.notification.recipient-hash-key, a key the database never sees
--    (kernel RecipientHash over KeyedHash), and the id of that key beside it.
--
-- 2. The rows written before this migration cannot be re-keyed: the clear recipient exists
--    nowhere (notification_pending is sealed and cleared once a row is settled). Their hash is
--    replaced by 32 random bytes: each row stays unique under (rule_id, event_id,
--    recipient_hash), and nothing of the number is left at rest (the log has no purge). Only the
--    one-hour de-duplication of rows older than this deploy is lost, an hour that is past. A
--    replaced row has recipient_hash_key_id null. A row still QUEUED keeps its sealed hold and is
--    retried as before; its replaced hash only means the hourly duplicate check cannot match it.
--
-- 3. The log says who a row is for without the number: the recipient's entity and the role the
--    audience reached (NotificationAudience.Recipient's entityId and roleCode; null for an
--    explicit recipient, a direct send, and every row before this migration). The quiet hours of
--    that entity are the ones that hold (CR-19A-12).
--
-- No policy changes: the columns fall under the five policies of V0054, and app_rw already
-- inserts every column (table-level INSERT); none of the three is ever updated.

ALTER TABLE kernel.notification_log
    ADD COLUMN recipient_hash_key_id text,
    ADD COLUMN recipient_entity_id uuid,
    ADD COLUMN audience_role text;

COMMENT ON COLUMN kernel.notification_log.recipient_hash IS
    'HMAC-SHA-256 of channel:recipient under the key named by recipient_hash_key_id, as hex; random hex on a row written before V0085. Never the recipient.';
COMMENT ON COLUMN kernel.notification_log.recipient_hash_key_id IS
    'Which configured key made recipient_hash (a hash prefix of the key, never the key); null on a row whose legacy hash V0085 replaced.';
COMMENT ON COLUMN kernel.notification_log.recipient_entity_id IS
    'The entity whose contact the recipient is, when the audience named it; its quiet hours hold.';
COMMENT ON COLUMN kernel.notification_log.audience_role IS
    'The role code the audience reached the recipient as (ACCOUNTS, MANAGER), when it named one.';

-- The migrator owns the table and has no policy on it, so FORCE is lifted for this one statement
-- (the owner then passes the policies) and put back straight after, in the same transaction
-- (the pattern of m2catalogue V0007). gen_random_bytes is pgcrypto's (kernel V0001).
ALTER TABLE kernel.notification_log NO FORCE ROW LEVEL SECURITY;
UPDATE kernel.notification_log
   SET recipient_hash = encode(gen_random_bytes(32), 'hex')
 WHERE recipient_hash_key_id IS NULL;
ALTER TABLE kernel.notification_log FORCE ROW LEVEL SECURITY;
