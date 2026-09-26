-- K-08, the review of 26 September: a replayable enrolment. Lane D (V0080 to V0099,
-- kernel/README.md).
--
-- The slice demands an Idempotency-Key on the enrolment call, but the replay store serves
-- command handlers of users only: a till whose network dropped after central committed the
-- enrolment retried with the same key and was refused, its code spent (doc 32 section 8: the
-- till holds no credential until this call answers). The code row now remembers the key it was
-- spent with; a retry with that key, the same hardware serial and the code still within its
-- lifetime is answered again (EnrolmentStore.enrol). Nothing else changes: the code is spent
-- once, and a retry with another key learns nothing.

ALTER TABLE kernel.device_enrolment_code
    ADD COLUMN spent_with_key varchar(200);

COMMENT ON COLUMN kernel.device_enrolment_code.spent_with_key IS
    'The Idempotency-Key the device spent the code with; a retry with the same key within the code''s lifetime is answered again';

GRANT UPDATE (spent_with_key) ON kernel.device_enrolment_code TO app_rw;
