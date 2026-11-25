package lk.coopfed.knoweb.m9integration.internal.notify;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
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
        if (envelope == null || envelope.isNull() || envelope.isMissingNode()) {
            throw new IllegalArgumentException("Price update event payload must not be null");
        }

        // Validate eventType if explicitly provided (either top-level or payload-level)
        String eventType = envelope.path("eventType").asText(null);
        if (eventType != null && !SUPPORTED_TYPES.contains(eventType)) {
            log.info("Ignoring event with unsupported eventType: {}", eventType);
            return null;
        }

        // Handle raw event payloads AND wrapped envelope payloads ({ eventType, eventId, payload: { ... } })
        JsonNode payload = envelope.hasNonNull("payload") ? envelope.get("payload") : envelope;
        if (!payload.isObject()) {
            throw new IllegalArgumentException("Price update event payload must be a JSON object");
        }

        if (eventType == null && payload.hasNonNull("eventType")) {
            eventType = payload.get("eventType").asText();
            if (!SUPPORTED_TYPES.contains(eventType)) {
                log.info("Ignoring event with unsupported payload eventType: {}", eventType);
                return null;
            }
        }

        String eventId = envelope.path("eventId").asText(payload.path("eventId").asText(""));

        // Extract and validate priceListId (check both priceListId and listId)
        String priceListStr =
                payload.path("priceListId").asText(payload.path("listId").asText(null));
        if (priceListStr == null || priceListStr.isBlank()) {
            throw new IllegalArgumentException("Price update event payload is missing required priceListId/listId");
        }
        UUID priceListId;
        try {
            priceListId = UUID.fromString(priceListStr.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid priceListId UUID in price update event: " + priceListStr, e);
        }

        // Extract and validate ownerEntityId (fallback to scope.entityId())
        String ownerEntityStr = payload.path("ownerEntityId").asText(null);
        UUID ownerEntityId = null;
        if (ownerEntityStr != null && !ownerEntityStr.isBlank()) {
            try {
                ownerEntityId = UUID.fromString(ownerEntityStr.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid ownerEntityId UUID in price update event: " + ownerEntityStr, e);
            }
        }
        if (ownerEntityId == null && scope != null) {
            ownerEntityId = scope.entityId();
        }
        if (ownerEntityId == null) {
            throw new IllegalArgumentException(
                    "Price update event carries no owner entity and no ambient scope exists");
        }

        String kind = payload.path("kind").asText("RETAIL");
        int version = payload.path("version").asInt(1);
        String applyFrom =
                payload.path("applyFrom").asText(payload.path("effectiveDate").asText("today"));

        String content = "Price Update Notice: " + kind + " price list (v" + version
                + ") is now active, effective from " + applyFrom + ". Visit your local branch for special rates.";
        String dedupKey =
                "price-update:" + priceListId + ":v" + version + ":" + (eventId.isBlank() ? priceListId : eventId);

        // Resolve active customer/accounts contact for the owner entity (respecting tenant boundary)
        Optional<String> recipientOpt = resolveCustomerContact(ownerEntityId);
        String recipient = recipientOpt.orElse(null);

        NotificationRequest request =
                new NotificationRequest(ownerEntityId, "SMS", recipient, "PRICE_UPDATE", content, dedupKey);

        log.info(
                "Dispatching price update alert for price list {} (v{}) for entity {}",
                priceListId,
                version,
                ownerEntityId);
        return gateway.dispatch(request, scope);
    }

    private Optional<String> resolveCustomerContact(UUID entityId) {
        List<String> contacts = jdbc.queryForList(
                """
                select address
                  from integration.notification_contact
                 where owner_entity_id = ?
                   and channel = 'SMS'
                   and status = 'ACTIVE'
                   and role_code in ('CUSTOMER', 'ACCOUNTS', 'MARKETING')
                 order by case role_code
                            when 'CUSTOMER' then 1
                            when 'MARKETING' then 2
                            when 'ACCOUNTS' then 3
                            else 4
                          end,
                          contact_id
                 limit 1
                """,
                String.class,
                entityId);
        return contacts.isEmpty() ? Optional.empty() : Optional.of(contacts.get(0));
    }
}
