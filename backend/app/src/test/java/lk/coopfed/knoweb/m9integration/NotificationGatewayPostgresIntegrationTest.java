package lk.coopfed.knoweb.m9integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NotificationChannel.FailureCategory;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.NotificationGateway;
import lk.coopfed.knoweb.m9integration.api.NotificationRequest;
import lk.coopfed.knoweb.m9integration.api.NotificationResult;
import lk.coopfed.knoweb.m9integration.internal.notify.BirthdayAlertNotificationConsumer;
import lk.coopfed.knoweb.m9integration.internal.notify.LowStockNotificationConsumer;
import lk.coopfed.knoweb.m9integration.internal.notify.MockSmsGateway;
import lk.coopfed.knoweb.m9integration.internal.notify.PriceUpdateNotificationConsumer;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries;
import lk.coopfed.knoweb.m9integration.query.NotificationRequestView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import lk.coopfed.knoweb.testsupport.TestIdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for the Central Notification Gateway and Message Alerts (Wave 3, PGL-M3-02).
 *
 * <p>Proves explicitly that:
 * <ul>
 *   <li>A supported event (low-stock from M5, price-update from M3, birthday from M7) creates a
 *       notification request and records the delivery outcome.</li>
 *   <li>A provider failure is correctly handled and retryable without triggering duplicate business actions.</li>
 *   <li>Duplicate events or retries are idempotently deduplicated.</li>
 *   <li>PostgreSQL Row-Level Security isolates notification requests across tenant scopes.</li>
 * </ul>
 */
class NotificationGatewayPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID SOCIETY_A = UUID.fromString("0190ea00-0000-7000-8000-000000000001");
    private static final UUID SOCIETY_B = UUID.fromString("0190ea00-0000-7000-8000-000000000002");
    private static final UUID USER = UUID.fromString("0190ea00-0000-7000-8000-000000000010");

    @DynamicPropertySource
    static void configureProvider(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.integration.sms.provider", () -> MockSmsGateway.NAME);
    }

    @Autowired
    NotificationGateway gateway;

    @Autowired
    LowStockNotificationConsumer lowStockConsumer;

    @Autowired
    PriceUpdateNotificationConsumer priceUpdateConsumer;

    @Autowired
    BirthdayAlertNotificationConsumer birthdayConsumer;

    @Autowired
    MockSmsGateway mockSmsGateway;

    @Autowired
    IntegrationQueries queries;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestRestTemplate http;

    @BeforeEach
    void setup() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("delete from integration.notification_request");
        admin.execute("delete from integration.notification_contact");
        mockSmsGateway.clear();
        kernel.reset();

        // Seed store contacts for Society A
        admin.update(
                """
                insert into integration.notification_contact
                    (contact_id, owner_entity_id, role_code, channel, address, language, status)
                values
                    (?, ?, 'STORE_KEEPER', 'SMS', '0771000001', 'en', 'ACTIVE'),
                    (?, ?, 'CUSTOMER', 'SMS', '0771000002', 'en', 'ACTIVE')
                """,
                Ids.next(),
                SOCIETY_A,
                Ids.next(),
                SOCIETY_A);
    }

    private ScopeContext scope(UUID entityId) {
        return ScopeContext.dev(USER, entityId, null);
    }

    private <T> T inScope(UUID entityId, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', '', true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    USER.toString(),
                    Ids.next().toString(),
                    entityId.toString());
            return work.get();
        });
    }

    private ResponseEntity<JsonNode> postHttp(String url, Object body, HttpHeaders headers) {
        HttpHeaders withKey = new HttpHeaders();
        withKey.addAll(headers);
        withKey.set("Idempotency-Key", Ids.next().toString());
        withKey.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, withKey), JsonNode.class);
    }

    private static HttpHeaders asUser(UUID entity) {
        return TestIdentityProvider.entityWideHeaders(USER, entity);
    }

    @Nested
    @DisplayName("Event Consumers & Notification Request Dispatch")
    class SupportedEventConsumers {

        @Test
        @DisplayName("Low-stock event from M5 creates a notification request and records delivery outcome")
        void lowStockEventCreatesNotificationRequestAndRecordsDeliveryOutcome() {
            UUID locationId = Ids.next();
            UUID skuId = Ids.next();
            UUID eventId = Ids.next();

            ObjectNode envelope = json.createObjectNode();
            envelope.put("eventType", LowStockNotificationConsumer.LOW_STOCK_TYPE);
            envelope.put("eventId", eventId.toString());

            ObjectNode payload = envelope.putObject("payload");
            payload.put("ownerEntityId", SOCIETY_A.toString());
            payload.put("locationId", locationId.toString());
            payload.put("skuId", skuId.toString());
            payload.put("onHandQty", new BigDecimal("5.000"));
            payload.put("reorderLevel", new BigDecimal("10.000"));

            NotificationResult result =
                    inScope(SOCIETY_A, () -> lowStockConsumer.onLowStock(envelope, scope(SOCIETY_A)));

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.status()).isEqualTo("SENT");
            assertThat(result.providerRef()).startsWith("mock-");
            assertThat(result.duplicate()).isFalse();

            // Verify stored notification request in database
            Optional<NotificationRequestView> request =
                    inScope(SOCIETY_A, () -> gateway.findById(result.requestId(), scope(SOCIETY_A)));
            assertThat(request).isPresent();
            NotificationRequestView view = request.get();
            assertThat(view.ownerEntityId()).isEqualTo(SOCIETY_A);
            assertThat(view.messageType()).isEqualTo("LOW_STOCK");
            assertThat(view.channel()).isEqualTo("SMS");
            assertThat(view.status()).isEqualTo("SENT");
            assertThat(view.attempts()).isEqualTo(1);
            assertThat(view.provider()).isEqualTo(MockSmsGateway.NAME);
            assertThat(view.providerRef()).isEqualTo(result.providerRef());
            assertThat(view.content()).contains("Low stock alert", skuId.toString());
            assertThat(view.recipientTag()).doesNotContain("0771000001"); // Masked, no PII

            // Verify mock provider took exactly 1 message
            assertThat(mockSmsGateway.sentMessages()).hasSize(1);
            assertThat(mockSmsGateway.sentMessages().get(0).phone()).isEqualTo("0771000001");

            // Verify audit trail
            assertThat(kernel.committedAudit())
                    .filteredOn(a -> "NOTIFICATION_SENT".equals(a.eventType()))
                    .hasSize(1);
        }

        @Test
        @DisplayName("Price-update event from M3 creates a notification request and records delivery outcome")
        void priceUpdateEventCreatesNotificationRequestAndRecordsDeliveryOutcome() {
            UUID priceListId = Ids.next();
            UUID eventId = Ids.next();

            ObjectNode envelope = json.createObjectNode();
            envelope.put("eventType", PriceUpdateNotificationConsumer.PRICE_LIST_PUBLISHED);
            envelope.put("eventId", eventId.toString());

            ObjectNode payload = envelope.putObject("payload");
            payload.put("ownerEntityId", SOCIETY_A.toString());
            payload.put("priceListId", priceListId.toString());
            payload.put("kind", "RETAIL");
            payload.put("version", 2);
            payload.put("applyFrom", "2026-11-05");

            NotificationResult result =
                    inScope(SOCIETY_A, () -> priceUpdateConsumer.onPriceUpdate(envelope, scope(SOCIETY_A)));

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.status()).isEqualTo("SENT");

            Optional<NotificationRequestView> request =
                    inScope(SOCIETY_A, () -> gateway.findById(result.requestId(), scope(SOCIETY_A)));
            assertThat(request).isPresent();
            assertThat(request.get().messageType()).isEqualTo("PRICE_UPDATE");
            assertThat(request.get().status()).isEqualTo("SENT");
            assertThat(request.get().content()).contains("Price Update Notice", "v2", "2026-11-05");
        }

        @Test
        @DisplayName(
                "Price-update raw event payload (PriceListPublished direct JSON) creates a notification request and records delivery outcome")
        void priceUpdateRawPayloadCreatesNotificationRequestAndRecordsDeliveryOutcome() {
            UUID priceListId = Ids.next();

            ObjectNode rawPayload = json.createObjectNode();
            rawPayload.put("priceListId", priceListId.toString());
            rawPayload.put("rootPriceListId", priceListId.toString());
            rawPayload.put("kind", "RETAIL");
            rawPayload.put("ownerEntityId", SOCIETY_A.toString());
            rawPayload.put("version", 3);
            rawPayload.put("applyFrom", "2026-11-10");

            NotificationResult result =
                    inScope(SOCIETY_A, () -> priceUpdateConsumer.onPriceUpdate(rawPayload, scope(SOCIETY_A)));

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.status()).isEqualTo("SENT");
            assertThat(result.duplicate()).isFalse();

            Optional<NotificationRequestView> request =
                    inScope(SOCIETY_A, () -> gateway.findById(result.requestId(), scope(SOCIETY_A)));
            assertThat(request).isPresent();
            NotificationRequestView view = request.get();
            assertThat(view.ownerEntityId()).isEqualTo(SOCIETY_A);
            assertThat(view.messageType()).isEqualTo("PRICE_UPDATE");
            assertThat(view.channel()).isEqualTo("SMS");
            assertThat(view.status()).isEqualTo("SENT");
            assertThat(view.content()).contains("Price Update Notice", "v3", "2026-11-10");
            assertThat(view.recipientTag()).doesNotContain("0771000002"); // Masked, no PII

            // Verify mock provider took message for the active contact of Society A
            assertThat(mockSmsGateway.sentMessages())
                    .anyMatch(msg ->
                            "0771000002".equals(msg.phone()) && msg.body().contains("v3"));
        }

        @Test
        @DisplayName("Price-update raw event payload is idempotently deduplicated on redelivery")
        void priceUpdateRawPayloadIdempotentDeduplication() {
            UUID priceListId = Ids.next();

            ObjectNode rawPayload = json.createObjectNode();
            rawPayload.put("priceListId", priceListId.toString());
            rawPayload.put("rootPriceListId", priceListId.toString());
            rawPayload.put("kind", "RETAIL");
            rawPayload.put("ownerEntityId", SOCIETY_A.toString());
            rawPayload.put("version", 1);
            rawPayload.put("applyFrom", "2026-11-10");

            NotificationResult first =
                    inScope(SOCIETY_A, () -> priceUpdateConsumer.onPriceUpdate(rawPayload, scope(SOCIETY_A)));
            assertThat(first).isNotNull();
            assertThat(first.duplicate()).isFalse();
            int countAfterFirst = mockSmsGateway.sentMessages().size();

            NotificationResult second =
                    inScope(SOCIETY_A, () -> priceUpdateConsumer.onPriceUpdate(rawPayload, scope(SOCIETY_A)));
            assertThat(second).isNotNull();
            assertThat(second.duplicate()).isTrue();
            assertThat(second.requestId()).isEqualTo(first.requestId());
            assertThat(mockSmsGateway.sentMessages()).hasSize(countAfterFirst);
        }

        @Test
        @DisplayName("Price-update event missing required priceListId throws IllegalArgumentException")
        void priceUpdateMissingPriceListIdThrows() {
            ObjectNode rawPayload = json.createObjectNode();
            rawPayload.put("kind", "RETAIL");
            rawPayload.put("ownerEntityId", SOCIETY_A.toString());

            assertThatThrownBy(() ->
                            inScope(SOCIETY_A, () -> priceUpdateConsumer.onPriceUpdate(rawPayload, scope(SOCIETY_A))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("missing required priceListId");
        }

        @Test
        @DisplayName(
                "Price-update event for entity without active contact records explicit actionable failure and does not use hardcoded fallback")
        void priceUpdateWithoutEligibleContactRecordsExplicitActionableFailure() {
            UUID priceListId = Ids.next();

            ObjectNode rawPayload = json.createObjectNode();
            rawPayload.put("priceListId", priceListId.toString());
            rawPayload.put("rootPriceListId", priceListId.toString());
            rawPayload.put("kind", "RETAIL");
            rawPayload.put("ownerEntityId", SOCIETY_B.toString()); // Society B has NO active contacts
            rawPayload.put("version", 1);
            rawPayload.put("applyFrom", "2026-11-10");

            int initialSentCount = mockSmsGateway.sentMessages().size();

            NotificationResult result =
                    inScope(SOCIETY_B, () -> priceUpdateConsumer.onPriceUpdate(rawPayload, scope(SOCIETY_B)));

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.status()).isEqualTo("FAILED");
            assertThat(result.error()).contains("No active notification contact configured for entity");

            // Verify database record has FAILED status, 0 attempts, and lastError
            Optional<NotificationRequestView> request =
                    inScope(SOCIETY_B, () -> gateway.findById(result.requestId(), scope(SOCIETY_B)));
            assertThat(request).isPresent();
            NotificationRequestView view = request.get();
            assertThat(view.ownerEntityId()).isEqualTo(SOCIETY_B);
            assertThat(view.status()).isEqualTo("FAILED");
            assertThat(view.attempts()).isEqualTo(0);
            assertThat(view.lastError()).contains("No active notification contact configured for entity");

            // Verify no SMS was dispatched to any fallback number
            assertThat(mockSmsGateway.sentMessages()).hasSize(initialSentCount);

            // Verify audit trail recorded NOTIFICATION_FAILED
            assertThat(kernel.committedAudit())
                    .filteredOn(a -> "NOTIFICATION_FAILED".equals(a.eventType()))
                    .hasSize(1);
        }

        @Test
        @DisplayName("Birthday alert event from M7 creates a notification request and records delivery outcome")
        void birthdayAlertEventCreatesNotificationRequestAndRecordsDeliveryOutcome() {
            UUID customerId = Ids.next();
            UUID eventId = Ids.next();

            ObjectNode envelope = json.createObjectNode();
            envelope.put("eventType", BirthdayAlertNotificationConsumer.BIRTHDAY_ALERT_TYPE);
            envelope.put("eventId", eventId.toString());

            ObjectNode payload = envelope.putObject("payload");
            payload.put("ownerEntityId", SOCIETY_A.toString());
            payload.put("customerId", customerId.toString());
            payload.put("customerRef", "MEM-0042");
            payload.put("greetingYear", 2026);

            NotificationResult result =
                    inScope(SOCIETY_A, () -> birthdayConsumer.onBirthdayAlert(envelope, scope(SOCIETY_A)));

            assertThat(result).isNotNull();
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.status()).isEqualTo("SENT");

            Optional<NotificationRequestView> request =
                    inScope(SOCIETY_A, () -> gateway.findById(result.requestId(), scope(SOCIETY_A)));
            assertThat(request).isPresent();
            assertThat(request.get().messageType()).isEqualTo("BIRTHDAY_ALERT");
            assertThat(request.get().content()).contains("Happy Birthday", "MEM-0042", "2026");
        }

        @Test
        @DisplayName("Draft price-update event does NOT trigger notifications")
        void draftPriceUpdateDoesNotTriggerNotification() {
            UUID priceListId = Ids.next();
            ObjectNode envelope = json.createObjectNode();
            envelope.put("eventType", "price_list.drafted.v1");
            envelope.put("eventId", Ids.next().toString());

            ObjectNode payload = envelope.putObject("payload");
            payload.put("ownerEntityId", SOCIETY_A.toString());
            payload.put("priceListId", priceListId.toString());
            payload.put("kind", "RETAIL");
            payload.put("version", 1);

            NotificationResult result =
                    inScope(SOCIETY_A, () -> priceUpdateConsumer.onPriceUpdate(envelope, scope(SOCIETY_A)));

            assertThat(result).isNull();
            assertThat(mockSmsGateway.sentMessages()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Reliability, Failures and Safe Retries")
    class ReliabilityAndRetries {

        @Test
        @DisplayName("Provider failure is handled and retryable without triggering duplicate business actions")
        void providerFailureHandledAndRetryableWithoutDuplicates() {
            UUID locationId = Ids.next();
            UUID skuId = Ids.next();

            ObjectNode envelope = json.createObjectNode();
            envelope.put("eventType", LowStockNotificationConsumer.LOW_STOCK_TYPE);
            envelope.put("eventId", Ids.next().toString());

            ObjectNode payload = envelope.putObject("payload");
            payload.put("ownerEntityId", SOCIETY_A.toString());
            payload.put("locationId", locationId.toString());
            payload.put("skuId", skuId.toString());
            payload.put("onHandQty", new BigDecimal("2.000"));
            payload.put("reorderLevel", new BigDecimal("10.000"));

            // 1. Simulate SMS provider failure on first attempt
            mockSmsGateway.failNext(1);
            mockSmsGateway.setFailureCategory(FailureCategory.TIMEOUT);

            NotificationResult firstResult =
                    inScope(SOCIETY_A, () -> lowStockConsumer.onLowStock(envelope, scope(SOCIETY_A)));

            assertThat(firstResult).isNotNull();
            assertThat(firstResult.status()).isEqualTo("PENDING");
            assertThat(firstResult.isSuccess()).isFalse();
            assertThat(firstResult.error()).contains("SendFailed: TIMEOUT");

            // Verify stored status is PENDING and attempt is 1
            NotificationRequestView pending = inScope(
                            SOCIETY_A, () -> gateway.findById(firstResult.requestId(), scope(SOCIETY_A)))
                    .orElseThrow();
            assertThat(pending.status()).isEqualTo("PENDING");
            assertThat(pending.attempts()).isEqualTo(1);
            assertThat(pending.lastError()).contains("SendFailed: TIMEOUT");
            assertThat(mockSmsGateway.sentMessages()).isEmpty();

            // 2. Retry delivery (provider is now available)
            NotificationResult retryResult =
                    inScope(SOCIETY_A, () -> gateway.retry(firstResult.requestId(), scope(SOCIETY_A)));

            assertThat(retryResult.isSuccess()).isTrue();
            assertThat(retryResult.status()).isEqualTo("SENT");
            assertThat(retryResult.providerRef()).startsWith("mock-");

            NotificationRequestView sent = inScope(
                            SOCIETY_A, () -> gateway.findById(firstResult.requestId(), scope(SOCIETY_A)))
                    .orElseThrow();
            assertThat(sent.status()).isEqualTo("SENT");
            assertThat(sent.attempts()).isEqualTo(2);
            assertThat(sent.lastError()).isNull();
            assertThat(mockSmsGateway.sentMessages()).hasSize(1);

            // 3. Retry again when already SENT — must be safe no-op returning duplicate without sending again
            NotificationResult duplicateRetry =
                    inScope(SOCIETY_A, () -> gateway.retry(firstResult.requestId(), scope(SOCIETY_A)));

            assertThat(duplicateRetry.isSuccess()).isTrue();
            assertThat(duplicateRetry.duplicate()).isTrue();
            assertThat(mockSmsGateway.sentMessages()).hasSize(1); // Provider NOT called a second time
        }

        @Test
        @DisplayName("Duplicate event dispatches with same dedupKey are idempotently ignored")
        void duplicateEventDispatchesAreDeduplicated() {
            String dedupKey = "low-stock:loc-1:sku-1:fixed-event-id";
            NotificationRequest req =
                    new NotificationRequest(SOCIETY_A, "SMS", "0771000001", "LOW_STOCK", "Stock is low", dedupKey);

            NotificationResult first = inScope(SOCIETY_A, () -> gateway.dispatch(req, scope(SOCIETY_A)));
            assertThat(first.isSuccess()).isTrue();
            assertThat(first.duplicate()).isFalse();
            assertThat(mockSmsGateway.sentMessages()).hasSize(1);

            // Dispatch again with same dedupKey
            NotificationResult second = inScope(SOCIETY_A, () -> gateway.dispatch(req, scope(SOCIETY_A)));
            assertThat(second.isSuccess()).isTrue();
            assertThat(second.duplicate()).isTrue();
            assertThat(second.requestId()).isEqualTo(first.requestId());
            assertThat(mockSmsGateway.sentMessages()).hasSize(1); // Provider was not invoked again
        }

        @Test
        @DisplayName("Exhausting max retries marks notification as FAILED and records audit")
        void exhaustingRetriesMarksFailedAndAudits() {
            UUID requestId = Ids.next();
            NotificationRequest req = new NotificationRequest(
                    requestId,
                    SOCIETY_A,
                    "SMS",
                    "0771000001",
                    "TEST_ALERT",
                    "Test message",
                    "dedup-exhaust-1",
                    Map.of());

            mockSmsGateway.failNext(5);

            // Attempt 1 -> PENDING
            NotificationResult res1 = inScope(SOCIETY_A, () -> gateway.dispatch(req, scope(SOCIETY_A)));
            assertThat(res1.status()).isEqualTo("PENDING");

            // Attempt 2 -> PENDING
            NotificationResult res2 = inScope(SOCIETY_A, () -> gateway.retry(requestId, scope(SOCIETY_A)));
            assertThat(res2.status()).isEqualTo("PENDING");

            // Attempt 3 -> FAILED (max attempts reached)
            NotificationResult res3 = inScope(SOCIETY_A, () -> gateway.retry(requestId, scope(SOCIETY_A)));
            assertThat(res3.status()).isEqualTo("FAILED");

            NotificationRequestView view = inScope(SOCIETY_A, () -> gateway.findById(requestId, scope(SOCIETY_A)))
                    .orElseThrow();
            assertThat(view.status()).isEqualTo("FAILED");
            assertThat(view.attempts()).isEqualTo(3);

            // Verify NOTIFICATION_FAILED audit row recorded
            assertThat(kernel.committedAudit())
                    .filteredOn(a -> "NOTIFICATION_FAILED".equals(a.eventType()))
                    .hasSize(1);
        }

        @Test
        @DisplayName("POST /v1/integration/notifications/{id}/retry endpoint retries failed delivery via HTTP")
        void retryEndpointRetriesFailedDeliveryViaHttp() {
            UUID requestId = Ids.next();
            NotificationRequest req = new NotificationRequest(
                    requestId,
                    SOCIETY_A,
                    "SMS",
                    "0771000001",
                    "LOW_STOCK",
                    "Low stock alert",
                    "dedup-http-retry-1",
                    Map.of());

            // Fail on first send so it is PENDING
            mockSmsGateway.failNext(1);
            NotificationResult initial = inScope(SOCIETY_A, () -> gateway.dispatch(req, scope(SOCIETY_A)));
            assertThat(initial.status()).isEqualTo("PENDING");

            // Now retry via HTTP endpoint
            ResponseEntity<JsonNode> response =
                    postHttp("/v1/integration/notifications/" + requestId + "/retry", null, asUser(SOCIETY_A));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("requestId").asText()).isEqualTo(requestId.toString());
            assertThat(response.getBody().path("status").asText()).isEqualTo("SENT");
            assertThat(response.getBody().path("providerRef").asText()).startsWith("mock-");

            NotificationRequestView view = inScope(SOCIETY_A, () -> gateway.findById(requestId, scope(SOCIETY_A)))
                    .orElseThrow();
            assertThat(view.status()).isEqualTo("SENT");
            assertThat(view.attempts()).isEqualTo(2);
        }

        @Test
        @DisplayName("IntegrationQueries.log returns unified rows including low-stock alerts")
        void logReturnsUnifiedAlerts() {
            NotificationRequest req = new NotificationRequest(
                    SOCIETY_A, "SMS", "0771000001", "LOW_STOCK", "Low stock alert SKU-99", "dedup-log-unified-1");
            NotificationResult res = inScope(SOCIETY_A, () -> gateway.dispatch(req, scope(SOCIETY_A)));
            assertThat(res.isSuccess()).isTrue();

            List<IntegrationQueries.LogEntry> logEntries =
                    inScope(SOCIETY_A, () -> queries.log("SENT", 10, scope(SOCIETY_A)));
            assertThat(logEntries).isNotEmpty();
            assertThat(logEntries)
                    .anyMatch(e -> e.notificationId().equals(res.requestId())
                            && "SMS".equals(e.channel())
                            && "low-stock-alert-sms".equals(e.templateId())
                            && "SENT".equals(e.status())
                            && "STORE_KEEPER".equals(e.audienceRole()));
        }
    }

    @Nested
    @DisplayName("PostgreSQL Row-Level Security")
    class RowLevelSecurityIsolation {

        @Test
        @DisplayName("PostgreSQL RLS isolates notification requests across societies")
        void rlsIsolatesNotificationRequests() {
            NotificationRequest reqA = new NotificationRequest(
                    SOCIETY_A, "SMS", "0771000001", "LOW_STOCK", "Society A Alert", "dedup-soc-a");
            NotificationResult resA = inScope(SOCIETY_A, () -> gateway.dispatch(reqA, scope(SOCIETY_A)));

            // Society A sees its request
            Optional<NotificationRequestView> seenByA =
                    inScope(SOCIETY_A, () -> gateway.findById(resA.requestId(), scope(SOCIETY_A)));
            assertThat(seenByA).isPresent();

            // Society B under RLS cannot see Society A's request
            Optional<NotificationRequestView> seenByB =
                    inScope(SOCIETY_B, () -> gateway.findById(resA.requestId(), scope(SOCIETY_B)));
            assertThat(seenByB).isEmpty();

            // Society B queries its requests: empty
            List<NotificationRequestView> listB =
                    inScope(SOCIETY_B, () -> queries.notificationRequests(null, 10, scope(SOCIETY_B)));
            assertThat(listB).isEmpty();
        }
    }
}
