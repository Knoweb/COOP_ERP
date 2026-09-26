-- ShedLock's JDBC provider, told to use the database clock (JobConfiguration, "usingDbTime"),
-- writes and compares timezone('utc', CURRENT_TIMESTAMP): a timestamp WITHOUT time zone that
-- reads as UTC. V0051 made lock_until and locked_at timestamptz (19A section 12 writes them so),
-- and PostgreSQL turns that naive value into a timestamptz through the session's time zone:
-- with the application's sessions in Asia/Colombo every lock time landed five and a half hours
-- early, an instance whose session ran in UTC disagreed with one that did not, and a row
-- expired by plain now() stayed locked for hours (found by the job runner tests of 26 Sep).
-- The columns become what ShedLock's own PostgreSQL DDL prescribes, timestamp in UTC; the
-- values are read back as UTC, which is right for a row written from a UTC session and puts a
-- row written from a Colombo session in the past, where an expired lock does no harm.
-- A deviation from the 19A DDL, recorded in docs/PROGRESS.md.

ALTER TABLE kernel.shedlock
    ALTER COLUMN lock_until TYPE timestamp USING timezone('utc', lock_until),
    ALTER COLUMN locked_at TYPE timestamp USING timezone('utc', locked_at);

COMMENT ON COLUMN kernel.shedlock.lock_until IS 'UTC, without time zone: what ShedLock''s PostgreSQL statements write and compare';
COMMENT ON COLUMN kernel.shedlock.locked_at IS 'UTC, without time zone: what ShedLock''s PostgreSQL statements write and compare';
