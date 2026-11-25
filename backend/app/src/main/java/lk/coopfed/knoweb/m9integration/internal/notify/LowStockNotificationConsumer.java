package lk.coopfed.knoweb.m9integration.internal.notify;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
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
 * Event consumer listening for low-stock notification facts from M5 Inventory (PGL-M3-02).
 *
 * <p>Formats and dispatches automated SMS alerts through the Central Notification Gateway.
 */
@Component
public class LowStockNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(LowStockNotificationConsumer.class);

    public static final String LOW_STOCK_TYPE = "inventory.low_stock_detected.v1";
    public static final String CONSUMER = "m9.notify.low_stock";

    private final NotificationGateway gateway;
    private final JdbcTemplate jdbc;

    public LowStockNotificationConsumer(NotificationGateway gateway, JdbcTemplate jdbc) {
        this.gateway = gateway;
        this.jdbc = jdbc;
    }

    @EventConsumer(
            types = {LOW_STOCK_TYPE},
            consumer = CONSUMER)
    public NotificationResult onLowStock(JsonNode envelope, ScopeContext scope) {
        if (envelope == null) {
            return null;
        }
        String eventType = envelope.path("eventType").asText(null);
        if (eventType != null && !LOW_STOCK_TYPE.equals(eventType)) {
            return null;
        }

        JsonNode payload = envelope.hasNonNull("payload") ? envelope.get("payload") : envelope;
        if (payload == null || !payload.isObject()) {
            return null;
        }

        String eventId = envelope.path("eventId").asText("");
        String skuId = payload.path("skuId").asText("");
        String locationId = payload.path("locationId").asText("");
        String onHandQty = payload.path("onHandQty").asText("0");
        String reorderLevel = payload.path("reorderLevel").asText("0");
        String ownerEntityStr = payload.path("ownerEntityId").asText(null);

        UUID ownerEntityId = (ownerEntityStr != null && !ownerEntityStr.isBlank())
                ? UUID.fromString(ownerEntityStr)
                : (scope != null ? scope.entityId() : null);

        if (ownerEntityId == null) {
            log.warn("Low stock event {} carries no owner entity, skipping", eventId);
            return null;
        }

        String recipient = resolveStoreContact(ownerEntityId, locationId);
        String content = "Low stock alert: SKU " + skuId + " at location " + locationId + " is at " + onHandQty
                + " (reorder level: " + reorderLevel + "). Please reorder.";
        String dedupKey = "low-stock:" + locationId + ":" + skuId + ":" + (eventId.isBlank() ? skuId : eventId);

        NotificationRequest request =
                new NotificationRequest(ownerEntityId, "SMS", recipient, "LOW_STOCK", content, dedupKey);

        log.info("Dispatching low stock alert for SKU {} at location {}", skuId, locationId);
        return gateway.dispatch(request, scope);
    }

    private String resolveStoreContact(UUID entityId, String locationId) {
        List<String> contacts = jdbc.queryForList(
                """
                select address
                  from integration.notification_contact
                 where owner_entity_id = ?
                   and channel = 'SMS'
                   and status = 'ACTIVE'
                   and role_code in ('STORE_KEEPER', 'MANAGER', 'ACCOUNTS')
                 limit 1
                """,
                String.class,
                entityId);
        return contacts.isEmpty() ? "0770000001" : contacts.get(0);
    }
}
