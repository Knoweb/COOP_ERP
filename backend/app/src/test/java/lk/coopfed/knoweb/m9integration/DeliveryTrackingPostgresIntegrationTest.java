package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.CompleteDeliveryTrip;
import lk.coopfed.knoweb.m9integration.api.CreateDeliveryTrip;
import lk.coopfed.knoweb.m9integration.api.DeliveryRecorded;
import lk.coopfed.knoweb.m9integration.api.DeliveryStatusUpdated;
import lk.coopfed.knoweb.m9integration.api.DeliveryTripCompleted;
import lk.coopfed.knoweb.m9integration.api.DeliveryTripCreated;
import lk.coopfed.knoweb.m9integration.api.RecordDeliveryDrop;
import lk.coopfed.knoweb.m9integration.api.RecordVehicleLocation;
import lk.coopfed.knoweb.m9integration.api.UpdateDeliveryStatus;
import lk.coopfed.knoweb.m9integration.api.VehicleLocationRecorded;
import lk.coopfed.knoweb.m9integration.internal.delivery.CompleteDeliveryTripHandler;
import lk.coopfed.knoweb.m9integration.internal.delivery.CreateDeliveryTripHandler;
import lk.coopfed.knoweb.m9integration.internal.delivery.RecordDeliveryDropHandler;
import lk.coopfed.knoweb.m9integration.internal.delivery.RecordVehicleLocationHandler;
import lk.coopfed.knoweb.m9integration.internal.delivery.UpdateDeliveryStatusHandler;
import lk.coopfed.knoweb.m9integration.query.DailyDeliverySummaryView;
import lk.coopfed.knoweb.m9integration.query.DeliveryDropView;
import lk.coopfed.knoweb.m9integration.query.DeliveryTrackingQueries;
import lk.coopfed.knoweb.m9integration.query.DeliveryTripSummaryView;
import lk.coopfed.knoweb.m9integration.query.LiveVehicleLocationView;
import lk.coopfed.knoweb.m9integration.query.MonthlyDeliveryReportView;
import lk.coopfed.knoweb.m9integration.query.VehicleRoutePointView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * PostgreSQL integration test proving delivery GPS tracking, route history, and delivery reports (M3-06).
 */
@Tag("integration")
class DeliveryTrackingPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID OWNER_ENTITY = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID OTHER_ENTITY = UUID.fromString("0190e9c0-0000-7000-8000-000000000002");
    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ORIGIN_LOCATION = UUID.fromString("0190e9c0-0000-7000-8000-000000000020");
    private static final UUID DEST_LOC_1 = UUID.fromString("0190e9c0-0000-7000-8000-000000000021");
    private static final UUID DEST_LOC_2 = UUID.fromString("0190e9c0-0000-7000-8000-000000000022");

    @Autowired
    Handles<RecordVehicleLocation, UUID> recordVehicleLocation;

    @Autowired
    Handles<CreateDeliveryTrip, UUID> createDeliveryTrip;

    @Autowired
    Handles<CompleteDeliveryTrip, Void> completeDeliveryTrip;

    @Autowired
    Handles<RecordDeliveryDrop, UUID> recordDeliveryDrop;

    @Autowired
    Handles<UpdateDeliveryStatus, Void> updateDeliveryStatus;

    @Autowired
    DeliveryTrackingQueries deliveryQueries;

    @Autowired
    TestRestTemplate http;

    private ScopeContext ownerScope() {
        Scope s = new Scope(OWNER_ENTITY, null);
        return new ScopeContext(
                USER_ID, null, OWNER_ENTITY, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private ScopeContext otherScope() {
        Scope s = new Scope(OTHER_ENTITY, null);
        return new ScopeContext(
                USER_ID, null, OTHER_ENTITY, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    private static HttpHeaders as(UUID entity) {
        return TestIdentityProvider.entityWideHeaders(USER_ID, entity);
    }

    private ResponseEntity<JsonNode> getHttp(String url, HttpHeaders headers) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postHttp(String url, Object body, HttpHeaders headers) {
        HttpHeaders withKey = new HttpHeaders();
        withKey.addAll(headers);
        withKey.set("Idempotency-Key", Ids.next().toString());
        withKey.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, withKey), JsonNode.class);
    }

    @BeforeEach
    void cleanTables() {
        superuserJdbc()
                .execute("truncate table integration.vehicle_location_update, integration.delivery_record, "
                        + "integration.delivery_trip restart identity cascade");
        kernel.reset();
    }

    @Test
    void deliveryTrackingLifecycleReportsAndRlsEndToEnd() {
        ScopeContext scope = ownerScope();
        LocalDate today = LocalDate.of(2026, 11, 3);

        // 1. Plan Delivery Trip 1
        UUID trip1Id = createDeliveryTrip.handle(
                new CreateDeliveryTrip(
                        "TRIP-20261103-01",
                        "VEH-COL-01",
                        null,
                        "DRV-101",
                        ORIGIN_LOCATION,
                        null,
                        today,
                        new BigDecimal("15420.0")),
                scope);
        assertThat(trip1Id).isNotNull();

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly(CreateDeliveryTripHandler.AUDIT_TRIP_CREATED);
        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(DeliveryTripCreated.class);
        kernel.reset();

        // 2. Schedule two package delivery drops on Trip 1
        UUID drop1Id = recordDeliveryDrop.handle(
                new RecordDeliveryDrop(
                        trip1Id,
                        "DEL-001",
                        DEST_LOC_1,
                        "REF-CUST-881",
                        10,
                        new BigDecimal("25.50"),
                        Instant.parse("2026-11-03T09:30:00Z"),
                        "Fragile goods"),
                scope);
        assertThat(drop1Id).isNotNull();

        UUID drop2Id = recordDeliveryDrop.handle(
                new RecordDeliveryDrop(
                        trip1Id,
                        "DEL-002",
                        DEST_LOC_2,
                        "REF-CUST-882",
                        4,
                        new BigDecimal("12.00"),
                        Instant.parse("2026-11-03T11:00:00Z"),
                        "Deliver to rear entrance"),
                scope);
        assertThat(drop2Id).isNotNull();

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly(
                        RecordDeliveryDropHandler.AUDIT_DELIVERY_RECORDED,
                        RecordDeliveryDropHandler.AUDIT_DELIVERY_RECORDED);
        assertThat(kernel.committedEvents()).hasSize(2).allMatch(e -> e instanceof DeliveryRecorded);
        kernel.reset();

        // 3. Ingest GPS location telemetry for vehicle VEH-COL-01
        // Telemetry Point 1: 08:30 (Trip starts moving -> auto-advances to IN_TRANSIT)
        recordVehicleLocation.handle(
                new RecordVehicleLocation(
                        "VEH-COL-01",
                        "GPS-BOX-101",
                        trip1Id,
                        new BigDecimal("6.927100"),
                        new BigDecimal("79.861200"),
                        new BigDecimal("35.5"),
                        90,
                        95,
                        Instant.parse("2026-11-03T08:30:00Z")),
                scope);

        // Telemetry Point 2: 09:30
        recordVehicleLocation.handle(
                new RecordVehicleLocation(
                        "VEH-COL-01",
                        "GPS-BOX-101",
                        trip1Id,
                        new BigDecimal("6.935000"),
                        new BigDecimal("79.870000"),
                        new BigDecimal("42.0"),
                        120,
                        90,
                        Instant.parse("2026-11-03T09:30:00Z")),
                scope);

        // Telemetry Point 3: 11:30 (Latest position)
        recordVehicleLocation.handle(
                new RecordVehicleLocation(
                        "VEH-COL-01",
                        "GPS-BOX-101",
                        trip1Id,
                        new BigDecimal("6.945000"),
                        new BigDecimal("79.885000"),
                        new BigDecimal("0.0"),
                        0,
                        82,
                        Instant.parse("2026-11-03T11:30:00Z")),
                scope);

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly(
                        RecordVehicleLocationHandler.AUDIT_LOCATION_RECORDED,
                        RecordVehicleLocationHandler.AUDIT_LOCATION_RECORDED,
                        RecordVehicleLocationHandler.AUDIT_LOCATION_RECORDED);
        assertThat(kernel.committedEvents()).hasSize(3).allMatch(e -> e instanceof VehicleLocationRecorded);
        kernel.reset();

        // 4. Verify Trip 1 status auto-transitioned to IN_TRANSIT
        DeliveryTripSummaryView tripView =
                deliveryQueries.getDeliveryTrip(trip1Id, scope).orElseThrow();
        assertThat(tripView.tripStatus()).isEqualTo("IN_TRANSIT");
        assertThat(tripView.startedAt()).isEqualTo(Instant.parse("2026-11-03T08:30:00Z"));
        assertThat(tripView.totalDrops()).isEqualTo(2);

        // 5. Verify Live Vehicle Location Query
        Optional<LiveVehicleLocationView> live = deliveryQueries.getLatestVehicleLocation("VEH-COL-01", scope);
        assertThat(live).isPresent();
        assertThat(live.get().vehicleId()).isEqualTo("VEH-COL-01");
        assertThat(live.get().latitude()).isEqualByComparingTo("6.945000");
        assertThat(live.get().longitude()).isEqualByComparingTo("79.885000");
        assertThat(live.get().batteryPercentage()).isEqualTo(82);

        List<LiveVehicleLocationView> liveList = deliveryQueries.listLiveVehicleLocations(scope);
        assertThat(liveList).hasSize(1);
        assertThat(liveList.get(0).vehicleId()).isEqualTo("VEH-COL-01");

        // 6. Verify Chronological Route History
        List<VehicleRoutePointView> route = deliveryQueries.getVehicleRouteHistory("VEH-COL-01", today, scope);
        assertThat(route).hasSize(3);
        assertThat(route.get(0).recordedAt()).isEqualTo(Instant.parse("2026-11-03T08:30:00Z"));
        assertThat(route.get(1).recordedAt()).isEqualTo(Instant.parse("2026-11-03T09:30:00Z"));
        assertThat(route.get(2).recordedAt()).isEqualTo(Instant.parse("2026-11-03T11:30:00Z"));

        // 7. Deliver Drop 1, Fail Drop 2
        updateDeliveryStatus.handle(
                new UpdateDeliveryStatus(drop1Id, "DELIVERED", null, "Signed by store manager"), scope);

        updateDeliveryStatus.handle(
                new UpdateDeliveryStatus(drop2Id, "FAILED", "Customer premises closed", "Will re-attempt tomorrow"),
                scope);

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly(
                        UpdateDeliveryStatusHandler.AUDIT_STATUS_UPDATED,
                        UpdateDeliveryStatusHandler.AUDIT_STATUS_UPDATED);
        assertThat(kernel.committedEvents()).hasSize(2).allMatch(e -> e instanceof DeliveryStatusUpdated);
        kernel.reset();

        DeliveryDropView drop1View =
                deliveryQueries.getDeliveryDrop(drop1Id, scope).orElseThrow();
        assertThat(drop1View.status()).isEqualTo("DELIVERED");
        assertThat(drop1View.deliveredAt()).isNotNull();

        DeliveryDropView drop2View =
                deliveryQueries.getDeliveryDrop(drop2Id, scope).orElseThrow();
        assertThat(drop2View.status()).isEqualTo("FAILED");
        assertThat(drop2View.failureReason()).isEqualTo("Customer premises closed");

        // 8. Conclude Trip 1 (end odometer 15485.5 -> 65.5 km distance)
        completeDeliveryTrip.handle(new CompleteDeliveryTrip(trip1Id, new BigDecimal("15485.5"), null), scope);

        assertThat(kernel.committedAudit())
                .extracting(a -> a.eventType())
                .containsExactly(CompleteDeliveryTripHandler.AUDIT_TRIP_COMPLETED);
        assertThat(kernel.committedEvents()).hasSize(1).first().isInstanceOf(DeliveryTripCompleted.class);
        kernel.reset();

        tripView = deliveryQueries.getDeliveryTrip(trip1Id, scope).orElseThrow();
        assertThat(tripView.tripStatus()).isEqualTo("COMPLETED");
        assertThat(tripView.totalDistanceKm()).isEqualByComparingTo("65.5");
        assertThat(tripView.deliveredDrops()).isEqualTo(1);
        assertThat(tripView.failedDrops()).isEqualTo(1);

        // 9. Verify End-of-Day Daily Delivery Summary Report
        DailyDeliverySummaryView dailySummary = deliveryQueries.getDailyDeliverySummary(today, null, scope);
        assertThat(dailySummary.date()).isEqualTo(today);
        assertThat(dailySummary.totalTrips()).isEqualTo(1);
        assertThat(dailySummary.completedTrips()).isEqualTo(1);
        assertThat(dailySummary.totalDeliveries()).isEqualTo(2);
        assertThat(dailySummary.deliveredCount()).isEqualTo(1);
        assertThat(dailySummary.failedCount()).isEqualTo(1);
        assertThat(dailySummary.pendingCount()).isEqualTo(0);
        assertThat(dailySummary.completionRatePercent()).isEqualByComparingTo("50.00");
        assertThat(dailySummary.totalDistanceKm()).isEqualByComparingTo("65.5");
        assertThat(dailySummary.activeVehiclesCount()).isEqualTo(1);
        assertThat(dailySummary.trips()).hasSize(1);

        // 10. Verify Month-End Delivery Report
        MonthlyDeliveryReportView monthlyReport = deliveryQueries.getMonthlyDeliveryReport("2026-11", null, scope);
        assertThat(monthlyReport.periodMonth()).isEqualTo("2026-11");
        assertThat(monthlyReport.totalTrips()).isEqualTo(1);
        assertThat(monthlyReport.completedTrips()).isEqualTo(1);
        assertThat(monthlyReport.totalDeliveries()).isEqualTo(2);
        assertThat(monthlyReport.deliveredCount()).isEqualTo(1);
        assertThat(monthlyReport.failedCount()).isEqualTo(1);
        assertThat(monthlyReport.deliverySuccessRate()).isEqualByComparingTo("50.00");
        assertThat(monthlyReport.totalDistanceKm()).isEqualByComparingTo("65.5");
        assertThat(monthlyReport.dailyBreakdown()).hasSize(1);
        assertThat(monthlyReport.dailyBreakdown().get(0).deliveredCount()).isEqualTo(1);
        assertThat(monthlyReport.vehicleBreakdown()).hasSize(1);
        assertThat(monthlyReport.vehicleBreakdown().get(0).vehicleId()).isEqualTo("VEH-COL-01");
        assertThat(monthlyReport.vehicleBreakdown().get(0).totalDistanceKm()).isEqualByComparingTo("65.5");

        // 11. Cross-Tenant Row-Level Security Isolation (OTHER_ENTITY cannot see OWNER_ENTITY's delivery data)
        ScopeContext other = otherScope();
        assertThat(deliveryQueries.listLiveVehicleLocations(other)).isEmpty();
        assertThat(deliveryQueries.getLatestVehicleLocation("VEH-COL-01", other))
                .isEmpty();
        assertThat(deliveryQueries.getVehicleRouteHistory("VEH-COL-01", today, other))
                .isEmpty();
        assertThat(deliveryQueries.getDeliveryTrip(trip1Id, other)).isEmpty();
        assertThat(deliveryQueries.getDeliveryDrop(drop1Id, other)).isEmpty();

        DailyDeliverySummaryView otherDaily = deliveryQueries.getDailyDeliverySummary(today, null, other);
        assertThat(otherDaily.totalTrips()).isEqualTo(0);
        assertThat(otherDaily.totalDeliveries()).isEqualTo(0);

        MonthlyDeliveryReportView otherMonthly = deliveryQueries.getMonthlyDeliveryReport("2026-11", null, other);
        assertThat(otherMonthly.totalTrips()).isEqualTo(0);
        assertThat(otherMonthly.totalDeliveries()).isEqualTo(0);
    }

    @Test
    void deliveryEndpointsHttpSurface() {
        HttpHeaders ownerHeaders = as(OWNER_ENTITY);

        // 1. HTTP POST location update
        Map<String, Object> locationReq = Map.of(
                "vehicleId",
                "VEH-KND-02",
                "deviceId",
                "GPS-KND-202",
                "latitude",
                7.290572,
                "longitude",
                80.633728,
                "speedKmh",
                40.0,
                "headingDegrees",
                45,
                "batteryPercentage",
                88,
                "recordedAt",
                "2026-11-03T12:00:00Z");

        ResponseEntity<JsonNode> postLocResp =
                postHttp("/v1/integration/delivery/locations", locationReq, ownerHeaders);
        assertThat(postLocResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(postLocResp.getBody().get("vehicleId").asText()).isEqualTo("VEH-KND-02");
        assertThat(postLocResp.getBody().get("updateId")).isNotNull();

        // 2. HTTP GET live locations
        ResponseEntity<JsonNode> listLiveResp = getHttp("/v1/integration/delivery/vehicles/live", ownerHeaders);
        assertThat(listLiveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listLiveResp.getBody().isArray()).isTrue();
        assertThat(listLiveResp.getBody()).isNotEmpty();

        // 3. HTTP GET live location for specific vehicle
        ResponseEntity<JsonNode> getLiveResp =
                getHttp("/v1/integration/delivery/vehicles/VEH-KND-02/live", ownerHeaders);
        assertThat(getLiveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getLiveResp.getBody().get("vehicleId").asText()).isEqualTo("VEH-KND-02");
        assertThat(getLiveResp.getBody().get("speedKmh").asDouble()).isEqualTo(40.0);

        // 4. HTTP GET route history
        ResponseEntity<JsonNode> getRouteResp =
                getHttp("/v1/integration/delivery/vehicles/VEH-KND-02/route?date=2026-11-03", ownerHeaders);
        assertThat(getRouteResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getRouteResp.getBody().get("vehicleId").asText()).isEqualTo("VEH-KND-02");
        assertThat(getRouteResp.getBody().get("pointsCount").asInt()).isEqualTo(1);

        // 5. HTTP GET daily summary
        ResponseEntity<JsonNode> dailyResp =
                getHttp("/v1/integration/delivery/reports/daily?date=2026-11-03", ownerHeaders);
        assertThat(dailyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dailyResp.getBody().get("totalTrips").asInt()).isGreaterThanOrEqualTo(0);

        // 6. HTTP GET monthly report
        ResponseEntity<JsonNode> monthlyResp =
                getHttp("/v1/integration/delivery/reports/monthly?period=2026-11", ownerHeaders);
        assertThat(monthlyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(monthlyResp.getBody().get("periodMonth").asText()).isEqualTo("2026-11");
    }
}
