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
import java.time.LocalDate;
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
import lk.coopfed.knoweb.m9integration.api.CreateDeliveryTrip;
import lk.coopfed.knoweb.m9integration.api.DeliveryTripCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class CreateDeliveryTripHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

    private CreateDeliveryTripHandler handler;

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID ORIGIN_LOC_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000020");

    private static ScopeContext ownScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID, null, ENTITY_ID, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    @BeforeEach
    void setUp() {
        handler = new CreateDeliveryTripHandler(jdbc, audit, events, clock);
    }

    @Test
    void rejectsMissingTripReference() {
        CreateDeliveryTrip cmd = new CreateDeliveryTrip(
                "  ",
                "VEH-001",
                null,
                "DRV-10",
                ORIGIN_LOC_ID,
                null,
                LocalDate.of(2026, 11, 3),
                BigDecimal.valueOf(1000));

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.trip_reference_required");
    }

    @Test
    void rejectsMissingVehicleId() {
        CreateDeliveryTrip cmd = new CreateDeliveryTrip(
                "TRIP-001",
                "",
                null,
                "DRV-10",
                ORIGIN_LOC_ID,
                null,
                LocalDate.of(2026, 11, 3),
                BigDecimal.valueOf(1000));

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.vehicle_id_required");
    }

    @Test
    void rejectsMissingOriginLocation() {
        CreateDeliveryTrip cmd = new CreateDeliveryTrip(
                "TRIP-001", "VEH-001", null, "DRV-10", null, null, LocalDate.of(2026, 11, 3), BigDecimal.valueOf(1000));

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.origin_location_required");
    }

    @Test
    void rejectsMissingTripDate() {
        CreateDeliveryTrip cmd = new CreateDeliveryTrip(
                "TRIP-001", "VEH-001", null, "DRV-10", ORIGIN_LOC_ID, null, null, BigDecimal.valueOf(1000));

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.trip_date_required");
    }

    @Test
    void rejectsDuplicateTripReference() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(ENTITY_ID), eq("TRIP-001")))
                .thenReturn(1);

        CreateDeliveryTrip cmd = new CreateDeliveryTrip(
                "TRIP-001",
                "VEH-001",
                null,
                "DRV-10",
                ORIGIN_LOC_ID,
                null,
                LocalDate.of(2026, 11, 3),
                BigDecimal.valueOf(1000));

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.trip_reference_duplicate");
    }

    @Test
    void createsDeliveryTripAuditsAndPublishesEvent() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(ENTITY_ID), eq("TRIP-001")))
                .thenReturn(0);

        CreateDeliveryTrip cmd = new CreateDeliveryTrip(
                "TRIP-001",
                "VEH-001",
                null,
                "DRV-10",
                ORIGIN_LOC_ID,
                null,
                LocalDate.of(2026, 11, 3),
                BigDecimal.valueOf(12500.5));

        UUID tripId = handler.handle(cmd, ownScope());

        assertThat(tripId).isNotNull();
        verify(audit).record(eq(CreateDeliveryTripHandler.AUDIT_TRIP_CREATED), any(), eq(null), any(), any());

        ArgumentCaptor<DeliveryTripCreated> eventCaptor = ArgumentCaptor.forClass(DeliveryTripCreated.class);
        verify(events).publish(eventCaptor.capture());

        DeliveryTripCreated event = eventCaptor.getValue();
        assertThat(event.tripId()).isEqualTo(tripId);
        assertThat(event.tripReference()).isEqualTo("TRIP-001");
        assertThat(event.vehicleId()).isEqualTo("VEH-001");
        assertThat(event.originLocationId()).isEqualTo(ORIGIN_LOC_ID);
        assertThat(event.tripDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }
}
