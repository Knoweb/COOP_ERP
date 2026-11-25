-- M3-05: Fingerprint Attendance OT to Payroll Integration (integration schema)
-- Staged flow: hr_employee -> attendance_record -> overtime_calculation -> payroll_contribution

-- ---------------------------------------------------------------------------------------------
-- 1. hr_employee: Authoritative, tenant-scoped employee record for attendance and payroll
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integration.hr_employee (
    employee_id      uuid          PRIMARY KEY,
    owner_entity_id  uuid          NOT NULL,
    employee_no      varchar(32)   NOT NULL,
    full_name        text          NOT NULL,
    nic              varchar(20),
    epf_no           varchar(32),
    hourly_rate      numeric(14,2) NOT NULL DEFAULT 0.00 CHECK (hourly_rate >= 0),
    location_id      uuid,
    status           text          NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    CONSTRAINT hr_employee_entity_no_uq UNIQUE (owner_entity_id, employee_no)
);

CREATE INDEX hr_employee_owner_idx ON integration.hr_employee (owner_entity_id, status);

ALTER TABLE integration.hr_employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.hr_employee FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.hr_employee FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_write ON integration.hr_employee FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY own_update ON integration.hr_employee FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN'
           AND owner_entity_id = kernel.scope_entity()
           AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()))
    WITH CHECK (kernel.scope_class() = 'OWN'
                AND owner_entity_id = kernel.scope_entity()
                AND (kernel.scope_location() IS NULL OR location_id = kernel.scope_location()));
CREATE POLICY fed_view ON integration.hr_employee FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.hr_employee FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- ---------------------------------------------------------------------------------------------
-- 2. attendance_record: Provider-neutral raw and validated attendance entries from biometric hardware
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integration.attendance_record (
    attendance_id      uuid          PRIMARY KEY,
    owner_entity_id    uuid          NOT NULL,
    external_record_id varchar(128)  NOT NULL,
    employee_id        uuid          NOT NULL REFERENCES integration.hr_employee (employee_id),
    device_identifier  varchar(64)   NOT NULL,
    source_provider    varchar(64)   NOT NULL,
    business_date      date          NOT NULL,
    clock_in           timestamptz   NOT NULL,
    clock_out          timestamptz   NOT NULL,
    worked_minutes     int           NOT NULL CHECK (worked_minutes >= 0),
    status             text          NOT NULL DEFAULT 'VALIDATED' CHECK (status IN ('RECORDED', 'VALIDATED', 'REJECTED')),
    rejection_reason   text,
    raw_payload        jsonb,
    created_at         timestamptz   NOT NULL,
    CONSTRAINT attendance_external_source_uq UNIQUE (owner_entity_id, source_provider, external_record_id),
    CHECK (clock_out >= clock_in)
);

CREATE INDEX attendance_owner_date_idx ON integration.attendance_record (owner_entity_id, business_date);
CREATE INDEX attendance_employee_idx ON integration.attendance_record (owner_entity_id, employee_id, business_date);

ALTER TABLE integration.attendance_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.attendance_record FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.attendance_record FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.attendance_record FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.attendance_record FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.attendance_record FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- ---------------------------------------------------------------------------------------------
-- 3. overtime_calculation: Calculated OT stage applying configurable working hour thresholds
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integration.overtime_calculation (
    calculation_id        uuid          PRIMARY KEY,
    attendance_id         uuid          NOT NULL REFERENCES integration.attendance_record (attendance_id),
    owner_entity_id       uuid          NOT NULL,
    employee_id           uuid          NOT NULL REFERENCES integration.hr_employee (employee_id),
    business_date         date          NOT NULL,
    worked_minutes        int           NOT NULL CHECK (worked_minutes >= 0),
    standard_minutes      int           NOT NULL CHECK (standard_minutes >= 0),
    qualifying_ot_minutes int           NOT NULL CHECK (qualifying_ot_minutes >= 0),
    calculated_ot_minutes int           NOT NULL CHECK (calculated_ot_minutes >= 0),
    ot_multiplier         numeric(5,2)  NOT NULL DEFAULT 1.50 CHECK (ot_multiplier > 0),
    calculated_amount     numeric(14,2) NOT NULL DEFAULT 0.00 CHECK (calculated_amount >= 0),
    status                text          NOT NULL DEFAULT 'PENDING_APPROVAL' CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'NO_OT_EARNED')),
    created_at            timestamptz   NOT NULL,
    CONSTRAINT ot_calculation_attendance_uq UNIQUE (attendance_id)
);

CREATE INDEX ot_calc_owner_status_idx ON integration.overtime_calculation (owner_entity_id, status);
CREATE INDEX ot_calc_employee_date_idx ON integration.overtime_calculation (owner_entity_id, employee_id, business_date);

ALTER TABLE integration.overtime_calculation ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.overtime_calculation FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.overtime_calculation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.overtime_calculation FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON integration.overtime_calculation FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.overtime_calculation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.overtime_calculation FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- ---------------------------------------------------------------------------------------------
-- 4. payroll_contribution: Separate auditable stage for approved OT contributing to payroll
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integration.payroll_contribution (
    contribution_id     uuid          PRIMARY KEY,
    owner_entity_id     uuid          NOT NULL,
    employee_id         uuid          NOT NULL REFERENCES integration.hr_employee (employee_id),
    calculation_id      uuid          NOT NULL REFERENCES integration.overtime_calculation (calculation_id),
    payroll_period      varchar(7)    NOT NULL, -- format YYYY-MM
    contribution_type   text          NOT NULL DEFAULT 'OVERTIME' CHECK (contribution_type IN ('OVERTIME', 'BONUS', 'ALLOWANCE')),
    approved_ot_minutes int           NOT NULL CHECK (approved_ot_minutes >= 0),
    approved_amount     numeric(14,2) NOT NULL DEFAULT 0.00 CHECK (approved_amount >= 0),
    approved_by         uuid          NOT NULL,
    approved_at         timestamptz   NOT NULL,
    approval_notes      text,
    status              text          NOT NULL DEFAULT 'APPROVED_PENDING_PAYROLL' CHECK (status IN ('APPROVED_PENDING_PAYROLL', 'POSTED_TO_PAYROLL', 'CANCELLED')),
    created_at          timestamptz   NOT NULL,
    CONSTRAINT payroll_contribution_calculation_uq UNIQUE (calculation_id)
);

CREATE INDEX payroll_contrib_period_idx ON integration.payroll_contribution (owner_entity_id, payroll_period, status);
CREATE INDEX payroll_contrib_employee_idx ON integration.payroll_contribution (owner_entity_id, employee_id, payroll_period);

ALTER TABLE integration.payroll_contribution ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.payroll_contribution FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.payroll_contribution FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.payroll_contribution FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.payroll_contribution FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.payroll_contribution FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

-- ---------------------------------------------------------------------------------------------
-- 5. Privileges: application user (app_rw) permissions
-- ---------------------------------------------------------------------------------------------
REVOKE ALL PRIVILEGES ON integration.hr_employee,
    integration.attendance_record,
    integration.overtime_calculation,
    integration.payroll_contribution
    FROM PUBLIC;

GRANT SELECT, INSERT ON integration.hr_employee TO app_rw;
GRANT UPDATE (full_name, nic, epf_no, hourly_rate, location_id, status, updated_at)
    ON integration.hr_employee TO app_rw;

GRANT SELECT, INSERT ON integration.attendance_record TO app_rw;

GRANT SELECT, INSERT ON integration.overtime_calculation TO app_rw;
GRANT UPDATE (status) ON integration.overtime_calculation TO app_rw;

GRANT SELECT, INSERT ON integration.payroll_contribution TO app_rw;

