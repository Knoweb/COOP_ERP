package lk.coopfed.knoweb.m9integration.internal.delivery;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import lk.coopfed.knoweb.m9integration.api.DeliveryStatusUpdated;
import lk.coopfed.knoweb.m9integration.api.UpdateDeliveryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class UpdateDeliveryStatusHandlerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditFacade audit = mock(AuditFacade.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final Instant fixedInstant = Instant.parse("2026-11-03T10:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

    private UpdateDeliveryStatusHandler handler;

    private static final UUID USER_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000010");
    private static final UUID ENTITY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000001");
    private static final UUID DELIVERY_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000050");
    private static final UUID TRIP_ID = UUID.fromString("0190e9c0-0000-7000-8000-000000000030");

    private static ScopeContext ownScope() {
        Scope s = new Scope(ENTITY_ID, null);
        return new ScopeContext(
                USER_ID, null, ENTITY_ID, List.of(s), s, PolicyClass.OWN, Set.of(), null, Locale.ENGLISH, null);
    }

    @BeforeEach
    void setUp() {
        handler = new UpdateDeliveryStatusHandler(jdbc, audit, events, clock);
    }

    @Test
    void rejectsMissingDeliveryId() {
        UpdateDeliveryStatus cmd = new UpdateDeliveryStatus(null, "DELIVERED", null, null);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.delivery_not_found");
    }

    @Test
    void rejectsInvalidStatus() {
        UpdateDeliveryStatus cmd = new UpdateDeliveryStatus(DELIVERY_ID, "UNKNOWN_STATUS", null, null);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.status_invalid");
    }

    @Test
    void rejectsFailedStatusWithoutReason() {
        UpdateDeliveryStatus cmd = new UpdateDeliveryStatus(DELIVERY_ID, "FAILED", "  ", null);

        assertThatThrownBy(() -> handler.handle(cmd, ownScope()))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m9.delivery.failure_reason_required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatesDeliveryStatusAuditsAndPublishesEvent() {
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(DELIVERY_ID), eq(ENTITY_ID)))
                .thenReturn(Map.of(
                        "delivery_id",
                        DELIVERY_ID,
                        "trip_id",
                        TRIP_ID,
                        "delivery_reference",
                        "DEL-001",
                        "status",
                        "PENDING"));

        UpdateDeliveryStatus cmd = new UpdateDeliveryStatus(DELIVERY_ID, "DELIVERED", null, "Received at desk");

        handler.handle(cmd, ownScope());

        verify(audit).record(eq(UpdateDeliveryStatusHandler.AUDIT_STATUS_UPDATED), any(), any(), any(), any());

        ArgumentCaptor<DeliveryStatusUpdated> eventCaptor = ArgumentCaptor.forClass(DeliveryStatusUpdated.class);
        verify(events).publish(eventCaptor.capture());

        DeliveryStatusUpdated event = eventCaptor.getValue();
        org.assertj.core.api.Assertions.assertThat(event.deliveryId()).isEqualTo(DELIVERY_ID);
        org.assertj.core.api.Assertions.assertThat(event.status()).isEqualTo("DELIVERED");
        org.assertj.core.api.Assertions.assertThat(event.updatedAt()).isEqualTo(fixedInstant);
    }
}
