package lk.coopfed.knoweb.m9integration.internal.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.NotificationDispatched;
import lk.coopfed.knoweb.m9integration.api.NotificationRequest;
import lk.coopfed.knoweb.m9integration.api.NotificationResult;
import lk.coopfed.knoweb.m9integration.query.NotificationRequestView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CentralNotificationGatewayTest {

    private JdbcTemplate jdbc;
    private AuditFacade audit;
    private EventPublisher events;
    private Clock clock;
    private MockSmsGateway smsGateway;
    private CentralNotificationGateway gateway;

    private static final UUID OWNER_ENTITY = UUID.fromString("0190ea00-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190ea00-0000-7000-8000-000000000010");

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditFacade.class);
        events = mock(EventPublisher.class);
        clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneId.of("UTC"));
        smsGateway = new MockSmsGateway();
        gateway = new CentralNotificationGateway(jdbc, audit, events, clock, List.of(smsGateway), "mock");
    }

    private ScopeContext scope(UUID entityId) {
        return ScopeContext.dev(USER, entityId, null);
    }

    @Test
    @DisplayName("Dispatch with valid request delivers via SmsGateway, records audit, and publishes event")
    void dispatchSuccessDeliversAndRecordsAudit() {
        UUID requestId = UUID.randomUUID();
        String dedupKey = "price-update:list-1:v1:event-1";
        NotificationRequest request = new NotificationRequest(
                requestId, OWNER_ENTITY, "SMS", "0771234567", "PRICE_UPDATE", "Price update", dedupKey, null);

        NotificationResult result = gateway.dispatch(request, scope(OWNER_ENTITY));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo("SENT");
        assertThat(result.providerRef()).isEqualTo("mock-" + requestId);
        assertThat(result.duplicate()).isFalse();

        // Verify SMS gateway actually sent message
        assertThat(smsGateway.sentMessages()).hasSize(1);
        assertThat(smsGateway.sentMessages().get(0).phone()).isEqualTo("0771234567");

        // Verify audit call
        verify(audit).record(eq("NOTIFICATION_SENT"), any(), any(), any(), any(), contains("Delivered via mock"));

        // Verify domain event
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(NotificationDispatched.class);
        NotificationDispatched dispatched = (NotificationDispatched) eventCaptor.getValue();
        assertThat(dispatched.status()).isEqualTo("SENT");
        assertThat(dispatched.messageType()).isEqualTo("PRICE_UPDATE");
    }

    @Test
    @DisplayName("Dispatch with missing recipient records FAILED request without provider send or endless retry")
    void dispatchMissingRecipientRecordsFailedRequest() {
        UUID requestId = UUID.randomUUID();
        String dedupKey = "price-update:list-1:v1:event-2";
        NotificationRequest request = new NotificationRequest(
                requestId, OWNER_ENTITY, "SMS", null, "PRICE_UPDATE", "Price update", dedupKey, null);

        NotificationResult result = gateway.dispatch(request, scope(OWNER_ENTITY));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("No active notification contact configured for entity");

        // Ensure SMS gateway was NEVER called
        assertThat(smsGateway.sentMessages()).isEmpty();

        // Verify NOTIFICATION_FAILED audit was recorded
        verify(audit).record(eq("NOTIFICATION_FAILED"), any(), any(), any(), any(), contains("Delivery failed"));

        // Verify domain event published with status FAILED
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events).publish(eventCaptor.capture());
        NotificationDispatched dispatched = (NotificationDispatched) eventCaptor.getValue();
        assertThat(dispatched.status()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Dispatch with existing dedupKey returns deduplicated result")
    void dispatchDeduplicatesExistingRequest() {
        UUID requestId = UUID.randomUUID();
        String dedupKey = "price-update:list-1:v1:event-3";
        NotificationRequest request = new NotificationRequest(
                requestId, OWNER_ENTITY, "SMS", "0771234567", "PRICE_UPDATE", "Price update", dedupKey, null);

        NotificationRequestView existingView = new NotificationRequestView(
                requestId,
                OWNER_ENTITY,
                "SMS",
                "***",
                "PRICE_UPDATE",
                "Price update",
                "SENT",
                1,
                "mock",
                "mock-" + requestId,
                null,
                dedupKey,
                null,
                clock.instant(),
                clock.instant());

        when(jdbc.query(anyString(), any(RowMapper.class), eq(dedupKey))).thenReturn(List.of(existingView));

        NotificationResult result = gateway.dispatch(request, scope(OWNER_ENTITY));

        assertThat(result).isNotNull();
        assertThat(result.duplicate()).isTrue();
        assertThat(result.status()).isEqualTo("SENT");
        assertThat(result.providerRef()).isEqualTo("mock-" + requestId);

        // No new SMS sent
        assertThat(smsGateway.sentMessages()).isEmpty();
    }
}
