-- K-12 scheduled jobs and K-13 business date (19A sections 12 and 13; doc 19 section 9). Lane C.
--
-- scheduled_job is the runtime view of the jobs the code registers: the code upserts every row
-- at start and never touches `enabled`, which is the on/off switch of the fleet console.
-- job_run is the history, insert-plus-finish only. shedlock is the row lock ShedLock takes per
-- run, so that two instances make one run per schedule. location_business_date is the
-- day-close state of a location: the business date is not the calendar date, it moves when
-- the location closes its day.

CREATE TABLE kernel.scheduled_job (
    name text PRIMARY KEY,
    module text NOT NULL,
    cron text,
    continuous boolean NOT NULL DEFAULT false,
    fixed_delay interval,
    lock_timeout interval NOT NULL,
    max_runtime interval NOT NULL,
    critical boolean NOT NULL DEFAULT false,
    enabled boolean NOT NULL DEFAULT true,
    registered_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- One of the two schedules, never both and never neither.
    CHECK ((cron IS NOT NULL AND NOT continuous AND fixed_delay IS NULL)
           OR (cron IS NULL AND continuous AND fixed_delay IS NOT NULL)),
    -- A lock outlives the run it protects (doc 19: "lock timeout longer than the job's maximum runtime").
    CHECK (lock_timeout > max_runtime)
);

CREATE TABLE kernel.job_run (
    run_id uuid PRIMARY KEY,
    name text NOT NULL REFERENCES kernel.scheduled_job (name),
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    instance text NOT NULL,
    outcome text
        CHECK (outcome IS NULL OR outcome IN ('SUCCESS', 'FAILED', 'TIMEOUT')),
    items_processed integer,
    error text,
    CHECK ((finished_at IS NULL) = (outcome IS NULL))
);

CREATE INDEX job_run_name_started_idx ON kernel.job_run (name, started_at DESC);

-- The table ShedLock's JDBC provider expects, by these column names.
CREATE TABLE kernel.shedlock (
    name text PRIMARY KEY,
    lock_until timestamptz NOT NULL,
    locked_at timestamptz NOT NULL,
    locked_by text NOT NULL
);

-- The platform's own tables: no tenant, every instance reads and writes them.
DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['scheduled_job', 'job_run', 'shedlock']
    LOOP
        EXECUTE format('ALTER TABLE kernel.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE kernel.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY platform ON kernel.%I FOR ALL TO app_rw USING (true) WITH CHECK (true)', t);
        EXECUTE format('REVOKE ALL PRIVILEGES ON kernel.%I FROM PUBLIC', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT ON kernel.scheduled_job TO app_rw;
-- Everything the code registers may change with the code; `enabled` is the console's and stays.
GRANT UPDATE (module, cron, continuous, fixed_delay, lock_timeout, max_runtime, critical, updated_at)
    ON kernel.scheduled_job TO app_rw;
GRANT SELECT, INSERT ON kernel.job_run TO app_rw;
GRANT UPDATE (finished_at, outcome, items_processed, error) ON kernel.job_run TO app_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON kernel.shedlock TO app_rw;

-- ---- K-13: the business date of a location ----------------------------------------------------

CREATE TABLE kernel.location_business_date (
    location_id uuid PRIMARY KEY,
    owner_entity_id uuid NOT NULL,
    business_date date NOT NULL,
    -- When the previous business date was closed; null until the first close.
    closed_at timestamptz,
    received_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE kernel.location_business_date ENABLE ROW LEVEL SECURITY;
ALTER TABLE kernel.location_business_date FORCE ROW LEVEL SECURITY;

-- The template; the day-close consumer and the cut-off job run in the OWN scope of the location's entity.
CREATE POLICY own_read ON kernel.location_business_date FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON kernel.location_business_date FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON kernel.location_business_date FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON kernel.location_business_date FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON kernel.location_business_date FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED'
           AND owner_entity_id = ANY (kernel.granted_entities()));

REVOKE ALL PRIVILEGES ON kernel.location_business_date FROM PUBLIC;
GRANT SELECT, INSERT ON kernel.location_business_date TO app_rw;
GRANT UPDATE (business_date, closed_at) ON kernel.location_business_date TO app_rw;
