package lk.coopfed.knoweb.m9integration.internal.notify;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDate;
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
 * Event consumer listening for birthday alert facts from M7 Customers (PGL-M3-02).
 *
 * <p>Formats and dispatches personalized birthday greetings through the Central Notification Gateway.
 */
@Component
public class BirthdayAlertNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(BirthdayAlertNotificationConsumer.class);

    public static final String BIRTHDAY_ALERT_TYPE = "customer.birthday_alert.v1";
    public static final String ALT_BIRTHDAY_ALERT_TYPE = "customers.birthday_alert.v1";
    public static final String CONSUMER = "m9.notify.birthday_alert";

    private static final Set<String> SUPPORTED_TYPES = Set.of(BIRTHDAY_ALERT_TYPE, ALT_BIRTHDAY_ALERT_TYPE);

    private final NotificationGateway gateway;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BirthdayAlertNotificationConsumer(NotificationGateway gateway, JdbcTemplate jdbc, Clock clock) {
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @EventConsumer(
            types = {BIRTHDAY_ALERT_TYPE, ALT_BIRTHDAY_ALERT_TYPE},
            consumer = CONSUMER)
    public NotificationResult onBirthdayAlert(JsonNode envelope, ScopeContext scope) {
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
        String customerId = payload.path("customerId").asText("");
        String customerRef = payload.path("customerRef").asText(customerId);
        int currentYear = LocalDate.now(clock).getYear();
        int greetingYear = payload.path("greetingYear").asInt(currentYear);
        String ownerEntityStr = payload.path("ownerEntityId").asText(null);

        UUID ownerEntityId = (ownerEntityStr != null && !ownerEntityStr.isBlank())
                ? UUID.fromString(ownerEntityStr)
                : (scope != null ? scope.entityId() : null);

        if (ownerEntityId == null) {
            log.warn("Birthday alert event {} carries no owner entity, skipping", eventId);
            return null;
        }

        String recipient = resolveCustomerPhone(customerId, ownerEntityId);
        String content = "Happy Birthday from your Cooperative Society! Wishing valued member " + customerRef
                + " health and prosperity in " + greetingYear + ".";
        String dedupKey = "birthday:" + customerId + ":" + greetingYear;

        NotificationRequest request =
                new NotificationRequest(ownerEntityId, "SMS", recipient, "BIRTHDAY_ALERT", content, dedupKey);

        log.info("Dispatching birthday greeting alert for customer {}", customerId);
        return gateway.dispatch(request, scope);
    }

    private String resolveCustomerPhone(String customerId, UUID entityId) {
        if (customerId != null && !customerId.isBlank()) {
            try {
                UUID custId = UUID.fromString(customerId);
                List<String> phones = jdbc.queryForList(
                        """
                        select phone
                          from customers.customer_phone
                         where customer_id = ?
                           and is_primary = true
                         limit 1
                        """,
                        String.class,
                        custId);
                if (!phones.isEmpty() && phones.get(0) != null && !phones.get(0).isBlank()) {
                    return phones.get(0);
                }
            } catch (Exception ignored) {
                // Ignore parsing errors and fallback to contact/default
            }
        }

        List<String> contacts = jdbc.queryForList(
                """
                select address
                  from integration.notification_contact
                 where owner_entity_id = ?
                   and channel = 'SMS'
                   and status = 'ACTIVE'
                 limit 1
                """,
                String.class,
                entityId);
        return contacts.isEmpty() ? "0770000003" : contacts.get(0);
    }
}

