package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.engine.Envelope;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OutboxWriter implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)+\\.v[0-9]+");

    private static final int MAX_PAYLOAD_BYTES = 8 * 1024;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ObjectProvider<PublishedEventListener> listeners;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper, ObjectProvider<PublishedEventListener> listeners) {

        this.jdbc = jdbc;
        this.mapper = mapper;
        this.listeners = listeners;
    }

    @Override
    public void publish(DomainEvent event) {

        if (event == null) {
            throw new IllegalArgumentException("Cannot publish a null event");
        }

        String type = typeOf(event);

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {

            throw new IllegalStateException("events.publish("
                    + type
                    + ") was called outside a transaction;"
                    + " call it inside the handler's"
                    + " @Transactional method, after audit.record");
        }

        UUID ownerEntityId = jdbc.queryForObject("select kernel.scope_entity()", UUID.class);

        if (ownerEntityId == null) {
            throw new IllegalStateException("Domain event " + type + " needs an active entity scope");
        }

        UUID locationId = jdbc.queryForObject("select kernel.scope_location()", UUID.class);

        UUID correlationId = jdbc.queryForObject(
                """
                        select nullif(
                            current_setting(
                                'app.correlation_id',
                                true
                            ),
                            ''
                        )::uuid
                        """,
                UUID.class);

        if (correlationId == null) {
            throw new IllegalStateException("Domain event " + type + " needs a correlation id");
        }

        UUID actorUserId = jdbc.queryForObject(
                """
                        select nullif(
                            current_setting(
                                'app.user_id',
                                true
                            ),
                            ''
                        )::uuid
                        """,
                UUID.class);

        UUID aggregateId = aggregateId(event);
        String aggregateType = aggregateType(type);

        Envelope<DomainEvent> envelope = new Envelope<>(Ids.next(), Instant.now(), event);

        JsonNode payload = mapper.valueToTree(envelope.getPayload());

        validatePayload(type, payload);

        String payloadJson = payload.toString();

        jdbc.update(
                """
                INSERT INTO kernel.event_outbox (
                    event_id,
                    event_type,
                    occurred_at,
                    occurred_local,
                    source,
                    source_seq,
                    owner_entity_id,
                    location_id,
                    aggregate_type,
                    aggregate_id,
                    correlation_id,
                    causation_id,
                    actor_user_id,
                    engine_version,
                    payload,
                    published_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    NULL,
                    'central',
                    nextval('kernel.central_source_seq'),
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    NULL,
                    ?,
                    NULL,
                    CAST(? AS jsonb),
                    NULL
                )
                """,
                envelope.getEventId(),
                type,
                Timestamp.from(envelope.getOccurredAt()),
                ownerEntityId,
                locationId,
                aggregateType,
                aggregateId,
                correlationId,
                actorUserId,
                payloadJson);

        // The caches of this instance learn of the change when it is real, not before: a
        // rollback must not leave a warmed cache of a state that never existed.
        publishedInThisTransaction().add(new Published(type, payload));
    }

    private record Published(String type, JsonNode payload) {}

    /**
     * One synchronisation per transaction, however many events it publishes: the events of this
     * transaction are kept as a transaction resource and handed to the listeners after commit.
     */
    @SuppressWarnings("unchecked")
    private List<Published> publishedInThisTransaction() {

        Object existing = TransactionSynchronizationManager.getResource(this);

        if (existing != null) {
            return (List<Published>) existing;
        }

        List<Published> published = new ArrayList<>();

        TransactionSynchronizationManager.bindResource(this, published);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (Published event : published) {
                    listeners.forEach(listener -> notifyListener(listener, event));
                }
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(OutboxWriter.this);
            }
        });

        return published;
    }

    /** The transaction is committed: a listener that fails must not fail the caller or the others. */
    private static void notifyListener(PublishedEventListener listener, Published event) {
        try {
            listener.published(event.type(), event.payload());
        } catch (RuntimeException failure) {
            log.warn(
                    "Published-event listener {} failed on {}",
                    listener.getClass().getName(),
                    event.type(),
                    failure);
        }
    }

    static String typeOf(DomainEvent event) {

        Class<?> eventClass = event.getClass();

        try {
            Field field = eventClass.getField("TYPE");

            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                throw new NoSuchFieldException();
            }

            String type = (String) field.get(null);

            if (type == null || !EVENT_TYPE.matcher(type).matches()) {

                throw new IllegalArgumentException(
                        eventClass.getName() + ".TYPE must look like " + "hello.greeting.registered.v1: " + type);
            }

            return type;

        } catch (NoSuchFieldException | IllegalAccessException e) {

            throw new IllegalArgumentException(eventClass.getName()
                    + " must declare: "
                    + "public static final String TYPE = "
                    + "\"module.thing.happened.v1\"");
        }
    }

    private static String aggregateType(String eventType) {

        String[] parts = eventType.split("\\.");

        if (parts.length < 3) {
            throw new IllegalArgumentException("Cannot derive aggregate type from " + eventType);
        }

        return parts[parts.length - 3];
    }

    private static UUID aggregateId(DomainEvent event) {

        Class<?> type = event.getClass();

        if (!type.isRecord()) {
            throw new IllegalArgumentException("Domain event " + type.getName() + " must be a record");
        }

        for (RecordComponent component : type.getRecordComponents()) {

            if (component.getType() != UUID.class) {
                continue;
            }

            String name = component.getName();

            if ("ownerEntityId".equals(name)
                    || "locationId".equals(name)
                    || "userId".equals(name)
                    || "deviceId".equals(name)) {
                continue;
            }

            if (!name.endsWith("Id")) {
                continue;
            }

            try {
                Object value = component.getAccessor().invoke(event);

                if (value instanceof UUID uuid) {
                    return uuid;
                }

            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("Cannot read aggregate id from " + type.getName(), e);
            }
        }

        throw new IllegalArgumentException("Domain event " + type.getName() + " needs an aggregate UUID component");
    }

    private static void validatePayload(String eventType, JsonNode payload) {

        int bytes = payload.toString().getBytes(StandardCharsets.UTF_8).length;

        if (bytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Domain event " + eventType + " payload exceeds 8 KB");
        }

        rejectSensitiveFields(eventType, payload);
    }

    /**
     * The words that name personal or secret data (AGENTS.md: never a phone number, NIC, password,
     * PIN or token in an event payload; doc 18: never personal-data values), matched as whole
     * words of the field name, split on case and on underscores. So {@code customerName},
     * {@code mobile_no} and {@code emailAddress} are refused, and {@code technicianId} or
     * {@code shippingId} (which merely contain "nic" and "pin") are not.
     */
    static final Set<String> FORBIDDEN_WORDS = Set.of(
            "name",
            "firstname",
            "lastname",
            "surname",
            "fullname",
            "phone",
            "mobile",
            "telephone",
            "msisdn",
            "whatsapp",
            "email",
            "mail",
            "address",
            "street",
            "city",
            "nic",
            "passport",
            "licence",
            "license",
            "password",
            "pin",
            "otp",
            "token",
            "secret",
            "credential",
            "dob",
            "birthdate",
            "dateofbirth",
            "birthday");

    /**
     * The display names of catalogue things that doc 18 lets an event carry (they name a product
     * or a unit, never a person), compared with case and underscores removed.
     */
    static final Set<String> ALLOWED_FIELDS = Set.of("nameen", "namesi", "nameta", "uomname", "productname");

    /** Words that stay refused even at the head of an identifier: a PIN code is still a PIN. */
    private static final Set<String> SECRET_WORDS =
            Set.of("pin", "otp", "password", "token", "secret", "credential", "nic", "passport");

    static boolean isForbiddenField(String key) {
        String[] words =
                key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase().split("[^a-z0-9]+");
        if (ALLOWED_FIELDS.contains(String.join("", words))) {
            return false;
        }
        // An identifier or a code refers to a row, it does not carry the value (addressId,
        // cityCode), unless its head is a secret (pinCode, tokenId).
        String last = words.length == 0 ? "" : words[words.length - 1];
        if (words.length > 1 && ("id".equals(last) || "code".equals(last))) {
            for (String word : words) {
                if (SECRET_WORDS.contains(word)) {
                    return true;
                }
            }
            return false;
        }
        for (String word : words) {
            if (FORBIDDEN_WORDS.contains(word)) {
                return true;
            }
        }
        // Joined forms a split cannot see: "firstname", "dateofbirth", "emailaddress".
        String joined = String.join("", words);
        for (String forbidden : FORBIDDEN_WORDS) {
            if (forbidden.length() >= 5 && joined.contains(forbidden)) {
                return true;
            }
        }
        return false;
    }

    private static void rejectSensitiveFields(String eventType, JsonNode node) {

        if (node.isObject()) {

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();

            while (fields.hasNext()) {

                Map.Entry<String, JsonNode> field = fields.next();

                if (isForbiddenField(field.getKey())) {

                    throw new IllegalArgumentException(
                            "Domain event " + eventType + " payload contains forbidden field " + field.getKey());
                }

                rejectSensitiveFields(eventType, field.getValue());
            }

        } else if (node.isArray()) {

            for (JsonNode child : node) {
                rejectSensitiveFields(eventType, child);
            }
        }
    }
}
