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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.CompleteDeliveryTrip;
import lk.coopfed.knoweb.m9integration.api.DeliveryTripCompleted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class CompleteDeliveryTripHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T18:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

    private CompleteDeliveryTripHandler handler;

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID TRIP_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000030");

    private static ScopeContext ownScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID, null, ENTITY_ID, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    @BeforeEach
    void setUp() {
        handler = new CompleteDeliveryTripHandler(jdbc, audit, events, clock);
    }

    @Test
    void rejectsMissingTripId() {
        CompleteDeliveryTrip cmd = new CompleteDeliveryTrip(null, BigDecimal.valueOf(1050), null);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.trip_not_found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAlreadyCompletedTrip() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(TRIP_ID), eq(ENTITY_ID)))
                .thenReturn(Map.of(
                        "trip_id", TRIP_ID,
                        "trip_reference", "TRIP-001",
                        "vehicle_id", "VEH-001",
                        "trip_status", "COMPLETED",
                        "start_odometer_km", BigDecimal.valueOf(1000)));

        CompleteDeliveryTrip cmd = new CompleteDeliveryTrip(TRIP_ID, BigDecimal.valueOf(1050), null);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.trip_completed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsEndOdometerLessThanStartOdometer() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(TRIP_ID), eq(ENTITY_ID)))
                .thenReturn(Map.of(
                        "trip_id", TRIP_ID,
                        "trip_reference", "TRIP-001",
                        "vehicle_id", "VEH-001",
                        "trip_status", "IN_TRANSIT",
                        "start_odometer_km", BigDecimal.valueOf(1000)));

        CompleteDeliveryTrip cmd = new CompleteDeliveryTrip(TRIP_ID, BigDecimal.valueOf(950), null);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.odometer_invalid");
    }

    @Test
    @SuppressWarnings("unchecked")
    void completesTripAuditsAndPublishesEvent() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(TRIP_ID), eq(ENTITY_ID)))
                .thenReturn(Map.of(
                        "trip_id", TRIP_ID,
                        "trip_reference", "TRIP-001",
                        "vehicle_id", "VEH-001",
                        "trip_status", "IN_TRANSIT",
                        "start_odometer_km", BigDecimal.valueOf(1000.0)));

        CompleteDeliveryTrip cmd = new CompleteDeliveryTrip(TRIP_ID, BigDecimal.valueOf(1085.5), null);

        handler.handle(cmd, ownScope());

        verify(audit).record(eq(CompleteDeliveryTripHandler.AUDIT_TRIP_COMPLETED), any(), any(), any(), any());

        ArgumentCaptor<DeliveryTripCompleted> eventCaptor = ArgumentCaptor.forClass(DeliveryTripCompleted.class);
        verify(events).publish(eventCaptor.capture());

        DeliveryTripCompleted event = eventCaptor.getValue();
        assertThat(event.tripId()).isEqualTo(TRIP_ID);
        assertThat(event.tripReference()).isEqualTo("TRIP-001");
        assertThat(event.vehicleId()).isEqualTo("VEH-001");
        assertThat(event.totalDistanceKm()).isEqualByComparingTo(BigDecimal.valueOf(85.5));
        assertThat(event.completedAt()).isEqualTo(fixedInstant);
    }
}
