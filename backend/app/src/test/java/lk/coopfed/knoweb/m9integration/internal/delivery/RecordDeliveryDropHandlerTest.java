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
import lk.coopfed.knoweb.m9integration.api.DeliveryRecorded;
import lk.coopfed.knoweb.m9integration.api.RecordDeliveryDrop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class RecordDeliveryDropHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

    private RecordDeliveryDropHandler handler;

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID TRIP_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000030");
    private static final UUID DEST_LOC_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000040");

    private static ScopeContext ownScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID, null, ENTITY_ID, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    @BeforeEach
    void setUp() {
        handler = new RecordDeliveryDropHandler(jdbc, audit, events, clock);
    }

    @Test
    void rejectsMissingTripId() {
        RecordDeliveryDrop cmd = new RecordDeliveryDrop(
                null, "DEL-001", DEST_LOC_ID, "CUST-01", 5, BigDecimal.valueOf(12.5), null, "Handle carefully");

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.trip_not_found");
    }

    @Test
    void rejectsMissingDeliveryReference() {
        RecordDeliveryDrop cmd = new RecordDeliveryDrop(
                TRIP_ID, " ", DEST_LOC_ID, "CUST-01", 5, BigDecimal.valueOf(12.5), null, "Handle carefully");

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.delivery_reference_required");
    }

    @Test
    void rejectsMissingDestinationLocation() {
        RecordDeliveryDrop cmd = new RecordDeliveryDrop(
                TRIP_ID, "DEL-001", null, "CUST-01", 5, BigDecimal.valueOf(12.5), null, "Handle carefully");

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.destination_location_required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsDropOnCompletedTrip() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(TRIP_ID), eq(ENTITY_ID)))
                .thenReturn("COMPLETED");

        RecordDeliveryDrop cmd = new RecordDeliveryDrop(
                TRIP_ID, "DEL-001", DEST_LOC_ID, "CUST-01", 5, BigDecimal.valueOf(12.5), null, "Handle carefully");

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.trip_completed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsDeliveryDropAuditsAndPublishesEvent() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(TRIP_ID), eq(ENTITY_ID)))
                .thenReturn("PLANNED");

        RecordDeliveryDrop cmd = new RecordDeliveryDrop(
                TRIP_ID, "DEL-001", DEST_LOC_ID, "CUST-01", 5, BigDecimal.valueOf(12.5), null, "Handle carefully");

        UUID deliveryId = handler.handle(cmd, ownScope());

        assertThat(deliveryId).isNotNull();
        verify(audit).record(eq(RecordDeliveryDropHandler.AUDIT_DELIVERY_RECORDED), any(), eq(null), any(), any());

        ArgumentCaptor<DeliveryRecorded> eventCaptor = ArgumentCaptor.forClass(DeliveryRecorded.class);
        verify(events).publish(eventCaptor.capture());

        DeliveryRecorded event = eventCaptor.getValue();
        assertThat(event.deliveryId()).isEqualTo(deliveryId);
        assertThat(event.tripId()).isEqualTo(TRIP_ID);
        assertThat(event.deliveryReference()).isEqualTo("DEL-001");
        assertThat(event.destinationLocationId()).isEqualTo(DEST_LOC_ID);
    }
}
