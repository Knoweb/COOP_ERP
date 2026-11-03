package lk.coopfed.knoweb.m9integration.internal.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.RecordVehicleLocation;
import lk.coopfed.knoweb.m9integration.api.VehicleLocationRecorded;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class RecordVehicleLocationHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

    private RecordVehicleLocationHandler handler;

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");

    private static ScopeContext ownScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID, null, ENTITY_ID, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    @BeforeEach
    void setUp() {
        handler = new RecordVehicleLocationHandler(jdbc, audit, events, clock);
    }

    @Test
    void rejectsMissingVehicleId() {
        RecordVehicleLocation cmd = new RecordVehicleLocation(
                null,
                "DEV-01",
                null,
                BigDecimal.valueOf(6.9271),
                BigDecimal.valueOf(79.8612),
                BigDecimal.valueOf(45.0),
                180,
                85,
                fixedInstant);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.vehicle_id_required");
    }

    @Test
    void rejectsMissingDeviceId() {
        RecordVehicleLocation cmd = new RecordVehicleLocation(
                "VEH-001",
                "  ",
                null,
                BigDecimal.valueOf(6.9271),
                BigDecimal.valueOf(79.8612),
                BigDecimal.valueOf(45.0),
                180,
                85,
                fixedInstant);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.device_id_required");
    }

    @Test
    void rejectsInvalidCoordinates() {
        RecordVehicleLocation invalidLat = new RecordVehicleLocation(
                "VEH-001",
                "DEV-01",
                null,
                BigDecimal.valueOf(95.0),
                BigDecimal.valueOf(79.8612),
                BigDecimal.valueOf(45.0),
                180,
                85,
                fixedInstant);

        assertThatThrownBy(() -> handler.handle(invalidLat, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.coordinates_invalid");

        RecordVehicleLocation invalidLng = new RecordVehicleLocation(
                "VEH-001",
                "DEV-01",
                null,
                BigDecimal.valueOf(6.9271),
                BigDecimal.valueOf(190.0),
                BigDecimal.valueOf(45.0),
                180,
                85,
                fixedInstant);

        assertThatThrownBy(() -> handler.handle(invalidLng, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.coordinates_invalid");
    }

    @Test
    void rejectsNonexistentTrip() {
        UUID tripId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(tripId), eq(ENTITY_ID)))
                .thenReturn(0);

        RecordVehicleLocation cmd = new RecordVehicleLocation(
                "VEH-001",
                "DEV-01",
                tripId,
                BigDecimal.valueOf(6.9271),
                BigDecimal.valueOf(79.8612),
                BigDecimal.valueOf(45.0),
                180,
                85,
                fixedInstant);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.trip_not_found");
    }

    @Test
    void recordsLocationAuditsAndPublishesEvent() {
        RecordVehicleLocation cmd = new RecordVehicleLocation(
                "VEH-001",
                "DEV-01",
                null,
                BigDecimal.valueOf(6.9271),
                BigDecimal.valueOf(79.8612),
                BigDecimal.valueOf(50.0),
                90,
                80,
                fixedInstant);

        UUID updateId = handler.handle(cmd, ownScope());

        assertThat(updateId).isNotNull();
        verify(audit).record(eq(RecordVehicleLocationHandler.AUDIT_LOCATION_RECORDED), any(), eq(null), any(), any());

        ArgumentCaptor<VehicleLocationRecorded> eventCaptor = ArgumentCaptor.forClass(VehicleLocationRecorded.class);
        verify(events).publish(eventCaptor.capture());

        VehicleLocationRecorded event = eventCaptor.getValue();
        assertThat(event.updateId()).isEqualTo(updateId);
        assertThat(event.vehicleId()).isEqualTo("VEH-001");
        assertThat(event.deviceId()).isEqualTo("DEV-01");
        assertThat(event.latitude()).isEqualByComparingTo(BigDecimal.valueOf(6.9271));
        assertThat(event.longitude()).isEqualByComparingTo(BigDecimal.valueOf(79.8612));
    }
}
