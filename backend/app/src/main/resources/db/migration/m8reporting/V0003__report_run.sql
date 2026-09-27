-- The report runs of the demo (28A section 3, report_run; section 7, render/ReportRunWorker):
-- a request to print a report to an A4 PDF, rendered on the worker. Not a projection: it is
-- written by command handlers (RequestReportRun, CompleteReportRun), which audit and publish.
--
-- Departures from 28A section 3, recorded in the module README: location_id (the requester's
-- location scope, so a shop's run stays the shop's), error_code for error, completed_at for
-- rendered_at, the parameters as the three the demo's reports take; scope, freshness and
-- stale_locations wait for the freshness resolver.
CREATE TABLE reporting.report_run (
    run_id          uuid         PRIMARY KEY,
    report_id       varchar(40)  NOT NULL,
    requested_by    uuid         NOT NULL,
    owner_entity_id uuid         NOT NULL,
    location_id     uuid,
    parameters      jsonb        NOT NULL,
    language        text         NOT NULL CHECK (language IN ('en', 'si', 'ta')),
    status          text         NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'READY', 'FAILED')),
    object_key      text,
    error_code      text,
    requested_at    timestamptz  NOT NULL,
    completed_at    timestamptz
);

CREATE INDEX report_run_by_owner ON reporting.report_run (owner_entity_id, requested_at);

ALTER TABLE reporting.report_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE reporting.report_run FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON reporting.report_run FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON reporting.report_run FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON reporting.report_run FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON reporting.report_run FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON reporting.report_run FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- A run's request never changes; its outcome is written once, by CompleteReportRun.
GRANT SELECT, INSERT ON reporting.report_run TO app_rw;
GRANT UPDATE (status, object_key, error_code, completed_at) ON reporting.report_run TO app_rw;
