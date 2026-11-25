package lk.coopfed.knoweb.m9integration.internal.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.NotificationGateway;
import lk.coopfed.knoweb.m9integration.api.NotificationRequest;
import lk.coopfed.knoweb.m9integration.api.NotificationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class PriceUpdateNotificationConsumerTest {

    private final ObjectMapper json = new ObjectMapper();
    private NotificationGateway gateway;
    private JdbcTemplate jdbc;
    private PriceUpdateNotificationConsumer consumer;

    private static final UUID OWNER_ENTITY = UUID.fromString("0190ea00-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("0190ea00-0000-7000-8000-000000000010");

    @BeforeEach
    void setUp() {
        gateway = mock(NotificationGateway.class);
        jdbc = mock(JdbcTemplate.class);
        consumer = new PriceUpdateNotificationConsumer(gateway, jdbc);
    }

    private ScopeContext scope(UUID entityId) {
        return ScopeContext.dev(USER, entityId, null);
    }

    @Test
    @DisplayName("Raw event payload (PriceListPublished direct JSON) successfully dispatches notification")
    void rawEventPayloadDispatchesNotification() {
        UUID priceListId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(String.class), eq(OWNER_ENTITY))).thenReturn(List.of("0771234567"));

        when(gateway.dispatch(any(NotificationRequest.class), any())).thenAnswer(inv -> {
            NotificationRequest req = inv.getArgument(0);
            return new NotificationResult(req.requestId(), "SENT", "mock-ref-1", null, false);
        });

        ObjectNode rawPayload = json.createObjectNode();
        rawPayload.put("priceListId", priceListId.toString());
        rawPayload.put("rootPriceListId", priceListId.toString());
        rawPayload.put("kind", "RETAIL");
        rawPayload.put("ownerEntityId", OWNER_ENTITY.toString());
        rawPayload.put("version", 2);
        rawPayload.put("applyFrom", "2026-11-10");

        NotificationResult result = consumer.onPriceUpdate(rawPayload, scope(OWNER_ENTITY));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo("SENT");

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(gateway).dispatch(captor.capture(), any());
        NotificationRequest request = captor.getValue();

        assertThat(request.ownerEntityId()).isEqualTo(OWNER_ENTITY);
        assertThat(request.channel()).isEqualTo("SMS");
        assertThat(request.recipient()).isEqualTo("0771234567");
        assertThat(request.messageType()).isEqualTo("PRICE_UPDATE");
        assertThat(request.content()).contains("RETAIL", "v2", "2026-11-10");
        assertThat(request.dedupKey()).contains("price-update:" + priceListId + ":v2");
    }

    @Test
    @DisplayName(
            "Wrapped envelope payload ({ eventType, eventId, payload: { ... } }) successfully dispatches notification")
    void wrappedEnvelopePayloadDispatchesNotification() {
        UUID priceListId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        when(jdbc.queryForList(anyString(), eq(String.class), eq(OWNER_ENTITY))).thenReturn(List.of("0779998888"));

        when(gateway.dispatch(any(NotificationRequest.class), any())).thenAnswer(inv -> {
            NotificationRequest req = inv.getArgument(0);
            return new NotificationResult(req.requestId(), "SENT", "mock-ref-2", null, false);
        });

        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventType", PriceUpdateNotificationConsumer.PRICE_LIST_PUBLISHED);
        envelope.put("eventId", eventId.toString());

        ObjectNode payload = envelope.putObject("payload");
        payload.put("priceListId", priceListId.toString());
        payload.put("kind", "WHOLESALE");
        payload.put("ownerEntityId", OWNER_ENTITY.toString());
        payload.put("version", 1);
        payload.put("applyFrom", "2026-11-15");

        NotificationResult result = consumer.onPriceUpdate(envelope, scope(OWNER_ENTITY));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(gateway).dispatch(captor.capture(), any());
        NotificationRequest request = captor.getValue();

        assertThat(request.recipient()).isEqualTo("0779998888");
        assertThat(request.content()).contains("WHOLESALE", "v1", "2026-11-15");
        assertThat(request.dedupKey()).isEqualTo("price-update:" + priceListId + ":v1:" + eventId);
    }

    @Test
    @DisplayName("Missing active contact does not use hardcoded 0770000002 fallback")
    void missingActiveContactPassesNullRecipientWithoutHardcodedFallback() {
        UUID priceListId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(String.class), eq(OWNER_ENTITY)))
                .thenReturn(List.of()); // No contacts found

        when(gateway.dispatch(any(NotificationRequest.class), any())).thenAnswer(inv -> {
            NotificationRequest req = inv.getArgument(0);
            return new NotificationResult(req.requestId(), "FAILED", null, "No recipient", false);
        });

        ObjectNode rawPayload = json.createObjectNode();
        rawPayload.put("priceListId", priceListId.toString());
        rawPayload.put("ownerEntityId", OWNER_ENTITY.toString());

        NotificationResult result = consumer.onPriceUpdate(rawPayload, scope(OWNER_ENTITY));

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("FAILED");

        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(gateway).dispatch(captor.capture(), any());
        NotificationRequest request = captor.getValue();

        // Must NOT be the old hardcoded "0770000002"
        assertThat(request.recipient()).isNull();
    }

    @Test
    @DisplayName("Malformed payload missing priceListId throws IllegalArgumentException")
    void malformedPayloadMissingPriceListIdThrows() {
        ObjectNode rawPayload = json.createObjectNode();
        rawPayload.put("kind", "RETAIL");
        rawPayload.put("ownerEntityId", OWNER_ENTITY.toString());

        assertThatThrownBy(() -> consumer.onPriceUpdate(rawPayload, scope(OWNER_ENTITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required priceListId");
    }

    @Test
    @DisplayName("Malformed payload with invalid UUID format throws IllegalArgumentException")
    void malformedPayloadInvalidUuidThrows() {
        ObjectNode rawPayload = json.createObjectNode();
        rawPayload.put("priceListId", "not-a-valid-uuid");
        rawPayload.put("ownerEntityId", OWNER_ENTITY.toString());

        assertThatThrownBy(() -> consumer.onPriceUpdate(rawPayload, scope(OWNER_ENTITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid priceListId UUID");
    }

    @Test
    @DisplayName("Null or non-object envelope throws IllegalArgumentException")
    void nullOrNonObjectThrows() {
        assertThatThrownBy(() -> consumer.onPriceUpdate(null, scope(OWNER_ENTITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        assertThatThrownBy(() -> consumer.onPriceUpdate(json.getNodeFactory().textNode("hello"), scope(OWNER_ENTITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a JSON object");
    }
}
