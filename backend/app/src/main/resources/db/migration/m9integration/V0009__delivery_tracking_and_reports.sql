-- M3-06: Delivery GPS, Route & Delivery Reports (Polgolla Program Wave 6)
-- Captures real-time vehicle GPS telemetry, delivery trips, package drops, and reconcilable reporting.

-- ---------------------------------------------------------------------------------------------
-- 1. delivery_trip: Planned and executed delivery trips by vehicle and driver
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integration.delivery_trip (
    trip_id                  uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    trip_reference           varchar(64)   NOT NULL,
    vehicle_id               varchar(64)   NOT NULL,
    driver_user_id           uuid,
    driver_code              varchar(32),
    origin_location_id       uuid          NOT NULL,
    destination_location_id  uuid,
    trip_date                date          NOT NULL,
    trip_status              varchar(32)   NOT NULL DEFAULT 'PLANNED'
                             CHECK (trip_status IN ('PLANNED', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED')),
    start_odometer_km        numeric(10,2) CHECK (start_odometer_km >= 0),
    end_odometer_km          numeric(10,2) CHECK (end_odometer_km >= 0),
    total_distance_km        numeric(10,2) NOT NULL DEFAULT 0.00 CHECK (total_distance_km >= 0),
    started_at               timestamptz,
    completed_at             timestamptz,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL,
    CONSTRAINT uq_delivery_trip_ref UNIQUE (owner_entity_id, trip_reference)
);

CREATE INDEX idx_delivery_trip_owner_date ON integration.delivery_trip (owner_entity_id, trip_date);
CREATE INDEX idx_delivery_trip_vehicle ON integration.delivery_trip (owner_entity_id, vehicle_id, trip_date);

ALTER TABLE integration.delivery_trip ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.delivery_trip FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.delivery_trip FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.delivery_trip FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON integration.delivery_trip FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.delivery_trip FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.delivery_trip FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON integration.delivery_trip TO app_rw;
GRANT UPDATE (trip_status, start_odometer_km, end_odometer_km, total_distance_km, started_at, completed_at, updated_at)
    ON integration.delivery_trip TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 2. delivery_record: Tracked drops / delivery points within trips
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integration.delivery_record (
    delivery_id              uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    trip_id                  uuid          NOT NULL REFERENCES integration.delivery_trip (trip_id),
    delivery_reference       varchar(64)   NOT NULL,
    destination_location_id  uuid          NOT NULL,
    recipient_ref            varchar(64),
    packages_count           int           NOT NULL DEFAULT 1 CHECK (packages_count >= 1),
    weight_kg                numeric(10,2) NOT NULL DEFAULT 0.00 CHECK (weight_kg >= 0),
    status                   varchar(32)   NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING', 'IN_TRANSIT', 'DELIVERED', 'FAILED', 'CANCELLED')),
    scheduled_at             timestamptz,
    delivered_at             timestamptz,
    failure_reason           text,
    notes                    text,
    created_at               timestamptz   NOT NULL,
    updated_at               timestamptz   NOT NULL,
    CONSTRAINT uq_delivery_record_ref UNIQUE (owner_entity_id, delivery_reference)
);

CREATE INDEX idx_delivery_record_trip ON integration.delivery_record (owner_entity_id, trip_id);
CREATE INDEX idx_delivery_record_destination ON integration.delivery_record (owner_entity_id, destination_location_id);

ALTER TABLE integration.delivery_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.delivery_record FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.delivery_record FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.delivery_record FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_update ON integration.delivery_record FOR UPDATE TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity())
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.delivery_record FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.delivery_record FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON integration.delivery_record TO app_rw;
GRANT UPDATE (status, delivered_at, failure_reason, notes, updated_at)
    ON integration.delivery_record TO app_rw;

-- ---------------------------------------------------------------------------------------------
-- 3. vehicle_location_update: Real-time GPS location updates from delivery vehicles / devices
-- ---------------------------------------------------------------------------------------------
CREATE TABLE integration.vehicle_location_update (
    update_id                uuid          PRIMARY KEY,
    owner_entity_id          uuid          NOT NULL,
    vehicle_id               varchar(64)   NOT NULL,
    device_id                varchar(64)   NOT NULL,
    trip_id                  uuid          REFERENCES integration.delivery_trip (trip_id),
    latitude                 numeric(10,7) NOT NULL CHECK (latitude >= -90.0 AND latitude <= 90.0),
    longitude                numeric(10,7) NOT NULL CHECK (longitude >= -180.0 AND longitude <= 180.0),
    speed_kmh                numeric(6,2)  NOT NULL DEFAULT 0.00 CHECK (speed_kmh >= 0),
    heading_degrees          int           CHECK (heading_degrees >= 0 AND heading_degrees <= 360),
    battery_percentage       int           CHECK (battery_percentage >= 0 AND battery_percentage <= 100),
    recorded_at              timestamptz   NOT NULL,
    received_at              timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX idx_vehicle_loc_latest ON integration.vehicle_location_update (owner_entity_id, vehicle_id, recorded_at DESC);
CREATE INDEX idx_vehicle_loc_trip ON integration.vehicle_location_update (owner_entity_id, trip_id, recorded_at ASC);

ALTER TABLE integration.vehicle_location_update ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.vehicle_location_update FORCE ROW LEVEL SECURITY;

CREATE POLICY own_read ON integration.vehicle_location_update FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY own_write ON integration.vehicle_location_update FOR INSERT TO app_rw
    WITH CHECK (kernel.scope_class() = 'OWN' AND owner_entity_id = kernel.scope_entity());
CREATE POLICY fed_view ON integration.vehicle_location_update FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'FEDERATION_VIEW');
CREATE POLICY ext_view ON integration.vehicle_location_update FOR SELECT TO app_rw
    USING (kernel.scope_class() = 'EXTERNAL_TIMEBOXED' AND owner_entity_id = ANY (kernel.granted_entities()));

GRANT SELECT, INSERT ON integration.vehicle_location_update TO app_rw;
