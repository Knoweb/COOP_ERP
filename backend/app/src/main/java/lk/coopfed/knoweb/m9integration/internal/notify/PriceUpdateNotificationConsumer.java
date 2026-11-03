package lk.coopfed.knoweb.m9integration.internal.notify;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m9integration.api.NotificationGateway;
import lk.coopfed.knoweb.m9integration.api.NotificationRequest;
import lk.coopfed.knoweb.m9integration.api.NotificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Event consumer listening for price-update messages from M3 Pricing (PGL-M3-02).
 *
 * <p>Formats and dispatches customer and member price-update alerts through the Central Notification Gateway.
 */
@Component
public class PriceUpdateNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(PriceUpdateNotificationConsumer.class);

    public static final String PRICE_LIST_PUBLISHED = "price_list.published.v1";
    public static final String CUSTOMER_PRICE_UPDATED = "pricing.customer_price_updated.v1";
    public static final String ALT_CUSTOMER_PRICE_UPDATED = "customer.price_updated.v1";
    public static final String CONSUMER = "m9.notify.price_update";

    private static final Set<String> SUPPORTED_TYPES =
            Set.of(PRICE_LIST_PUBLISHED, CUSTOMER_PRICE_UPDATED, ALT_CUSTOMER_PRICE_UPDATED);

    private final NotificationGateway gateway;
    private final JdbcTemplate jdbc;

    public PriceUpdateNotificationConsumer(NotificationGateway gateway, JdbcTemplate jdbc) {
        this.gateway = gateway;
        this.jdbc = jdbc;
    }

    @EventConsumer(
            types = {PRICE_LIST_PUBLISHED, CUSTOMER_PRICE_UPDATED, ALT_CUSTOMER_PRICE_UPDATED},
            consumer = CONSUMER)
    public NotificationResult onPriceUpdate(JsonNode envelope, ScopeContext scope) {
        if (envelope == null) {
            return null;
        }
        String eventType = envelope.path("eventType").asText(null);
        if (eventType != null && !SUPPORTED_TYPES.contains(eventType)) {
            return null;
        }

        JsonNode payload = envelope.path("payload");
        if (payload.isMissingNode() || payload.isNull()) {
            return null;
        }

        String eventId = envelope.path("eventId").asText("");
        String priceListId =
                payload.path("priceListId").asText(payload.path("listId").asText(""));
        String kind = payload.path("kind").asText("RETAIL");
        int version = payload.path("version").asInt(1);
        String applyFrom =
                payload.path("applyFrom").asText(payload.path("effectiveDate").asText("today"));
        String ownerEntityStr = payload.path("ownerEntityId").asText(null);

        UUID ownerEntityId = (ownerEntityStr != null && !ownerEntityStr.isBlank())
                ? UUID.fromString(ownerEntityStr)
                : (scope != null ? scope.entityId() : null);

        if (ownerEntityId == null) {
            log.warn("Price update event {} carries no owner entity, skipping", eventId);
            return null;
        }

        String recipient = resolveCustomerContact(ownerEntityId);
        String content = "Price Update Notice: " + kind + " price list (v" + version
                + ") is now active, effective from " + applyFrom + ". Visit your local branch for special rates.";
        String dedupKey =
                "price-update:" + priceListId + ":v" + version + ":" + (eventId.isBlank() ? priceListId : eventId);

        NotificationRequest request =
                new NotificationRequest(ownerEntityId, "SMS", recipient, "PRICE_UPDATE", content, dedupKey);

        log.info("Dispatching price update alert for price list {} (v{})", priceListId, version);
        return gateway.dispatch(request, scope);
    }

    private String resolveCustomerContact(UUID entityId) {
        List<String> contacts = jdbc.queryForList(
                """
                select address
                  from integration.notification_contact
                 where owner_entity_id = ?
                   and channel = 'SMS'
                   and status = 'ACTIVE'
                   and role_code in ('CUSTOMER', 'ACCOUNTS', 'MARKETING')
                 limit 1
                """,
                String.class,
                entityId);
        return contacts.isEmpty() ? "0770000002" : contacts.get(0);
    }
}
