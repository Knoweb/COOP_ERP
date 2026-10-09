package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnBean(BrokerAdapter.class)
public class EventConsumerDispatcher {

    static final String AUDIT_DEAD_LETTERED = "EVENT_CONSUMER_DEAD_LETTERED";
    static final String AUDIT_COUNTERPARTY_REFUSED = "EVENT_COUNTERPARTY_REFUSED";

    private static final Logger log = LoggerFactory.getLogger(EventConsumerDispatcher.class);

    private final EventConsumerRegistry registry;
    private final InboxGuard inbox;
    private final DeadLetter deadLetter;
    private final AuditFacade audit;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public EventConsumerDispatcher(
            EventConsumerRegistry registry,
            InboxGuard inbox,
            DeadLetter deadLetter,
            AuditFacade audit,
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {

        this.registry = registry;
        this.inbox = inbox;
        this.deadLetter = deadLetter;
        this.audit = audit;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public DeliveryResult deliver(String consumer, OutboxMessage message, int attempt) {

        if (attempt < 1) {
            throw new IllegalArgumentException("Attempt starts at 1");
        }

        EventConsumerRegistry.Registration registration = registry.find(consumer, message.eventType())
                .orElseThrow(() -> new PoisonMessageException(
                        "No @EventConsumer registered for " + consumer + " / " + message.eventType()));

        // What is read from the envelope fails the same way on every delivery (no owner entity,
        // a payload that is not JSON, a counterparty consumer handed a till's event): poison, not
        // a handler failure worth retrying.
        ScopeContext scope;
        String payload;
        UUID document = null;

        try {
            payload = payloadFor(registration, message);
            if (registration.party() == EventConsumer.Party.COUNTERPARTY) {
                JsonNode fields = mapper.readTree(payload);
                UUID counterparty = uuidField(fields, "counterpartyEntityId");
                if (counterparty == null) {
                    // Not a two-party event for this consumer (one published before the field
                    // existed, or a document with no other party): passed over, not an error.
                    log.debug("Event {} names no counterparty; nothing for {} to do", message.eventId(), consumer);
                    return DeliveryResult.APPLIED;
                }
                document = uuidField(fields, "documentId");
                scope = counterpartyScope(message, counterparty, document);
            } else {
                scope = systemScope(message);
            }
        } catch (RuntimeException | java.io.IOException unreadable) {
            throw new PoisonMessageException(
                    "Event " + message.eventId() + " cannot be delivered to " + consumer + ": " + unreadable,
                    unreadable);
        }

        UUID documentId = document;

        try {

            Boolean applied = transaction.execute(status -> {
                applyScope(scope);

                if (registration.party() == EventConsumer.Party.COUNTERPARTY) {
                    requireDocumentOfTheParties(message, scope, documentId, consumer);
                }

                return inbox.applyOnce(consumer, message.eventId(), () -> registration.invoke(payload, scope, mapper));
            });

            return Boolean.TRUE.equals(applied) ? DeliveryResult.APPLIED : DeliveryResult.DUPLICATE;

        } catch (CounterpartyRefused refused) {

            // A counterparty that is not the document's: a module tried to write into an entity's
            // scope it has no business in. Never retried; the runtime dead-letters it with the
            // reason. The attempt is recorded in the owner's scope, in a transaction of its own (the
            // delivery's was rolled back), so an auditor of cross-scope attempts finds it.
            recordRefusal(consumer, message, documentId, scope.entityId(), refused.getMessage());
            throw refused;

        } catch (PoisonMessageException poison) {

            throw poison;

        } catch (RuntimeException failure) {

            if (attempt < 3) {
                return DeliveryResult.RETRY;
            }

            String error = errorText(failure);

            transaction.executeWithoutResult(status -> {
                applyScope(scope);

                inbox.markFailed(consumer, message.eventId(), error);

                audit.record(
                        AUDIT_DEAD_LETTERED,
                        Subject.of("event", message.eventId()),
                        null,
                        Map.of("consumer", consumer, "eventType", message.eventType(), "attempts", attempt),
                        scope,
                        "Consumer failed after " + attempt + " attempts");
            });

            deadLetter.send(consumer, message, attempt, error);

            return DeliveryResult.DEAD_LETTERED;
        }
    }

    /**
     * A consumer of one type gets the payload; a consumer of every type ("*", the notification
     * dispatcher of K-10) gets the envelope with the payload inside, because the type and the
     * event id are what it matches on.
     */
    private String payloadFor(EventConsumerRegistry.Registration registration, OutboxMessage message) {
        if (!"*".equals(registration.eventType())) {
            return message.payload();
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode envelope = mapper.createObjectNode();
            envelope.put("eventType", message.eventType());
            envelope.put("eventId", message.eventId().toString());
            envelope.put("ownerEntityId", message.ownerEntityId().toString());
            envelope.put("occurredAt", message.occurredAt().toString());
            envelope.set("payload", mapper.readTree(message.payload()));
            return mapper.writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Event payload is not JSON: " + message.eventId(), e);
        }
    }

    private void applyScope(ScopeContext scope) {

        jdbc.queryForList(
                """
                SELECT
                    set_config('app.user_id', '', true),
                    set_config('app.correlation_id', ?, true),
                    set_config('app.scope_entity_id', ?, true),
                    set_config('app.scope_location_id', ?, true),
                    set_config('app.scope_class', 'OWN', true),
                    set_config('app.granted_entities', '{}', true)
                """,
                scope.correlationId().toString(),
                scope.entityId().toString(),
                scope.locationId() == null ? "" : scope.locationId().toString());
    }

    /**
     * The second check of counterparty delivery (CR-19A-13), inside the counterparty's scope: the
     * document the event names is the owner's, and the named counterparty is its
     * {@code counterparty_entity_id}. Read through {@code kernel.document}'s own {@code
     * party_read}, which in this scope shows the row only when the counterparty is this entity, so
     * the policy and the predicate say the same thing twice. A document that does not answer
     * is poison: the event is never delivered to this consumer, and nobody retries it.
     */
    private void requireDocumentOfTheParties(
            OutboxMessage message, ScopeContext scope, UUID documentId, String consumer) {

        if (documentId == null) {
            throw new CounterpartyRefused(
                    "Event " + message.eventId() + " names a counterparty but no document; refused for " + consumer);
        }

        Integer found = jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM kernel.document
                 WHERE document_id = ?
                   AND owner_entity_id = ?
                   AND counterparty_entity_id = ?
                """,
                Integer.class,
                documentId,
                message.ownerEntityId(),
                scope.entityId());

        if (found == null || found == 0) {
            throw new CounterpartyRefused("Event " + message.eventId() + " names " + scope.entityId()
                    + " as the counterparty of document " + documentId
                    + ", which the document does not; refused for " + consumer);
        }
    }

    /** The second check of counterparty delivery failed: poison, and recorded (M7M8M9-09). */
    static final class CounterpartyRefused extends PoisonMessageException {

        CounterpartyRefused(String message) {
            super(message);
        }
    }

    /**
     * EVENT_COUNTERPARTY_REFUSED in the owner's OWN scope, which is where the event came from and
     * where the owner's auditors look. Identifiers only: the consumer, the event, the document and
     * the entity it named. A failure to record is logged and does not change the refusal.
     */
    private void recordRefusal(String consumer, OutboxMessage message, UUID documentId, UUID named, String reason) {
        try {
            ScopeContext owner = systemScope(message);
            transaction.executeWithoutResult(status -> {
                applyScope(owner);

                Map<String, Object> after = new java.util.LinkedHashMap<>();
                after.put("consumer", consumer);
                after.put("eventType", message.eventType());
                after.put("documentId", documentId == null ? null : documentId.toString());
                after.put("counterpartyEntityId", named.toString());

                audit.record(
                        AUDIT_COUNTERPARTY_REFUSED, Subject.of("event", message.eventId()), null, after, owner, reason);
            });
        } catch (RuntimeException e) {
            log.error("Counterparty refusal of event {} for {} could not be audited", message.eventId(), consumer, e);
        }
    }

    private static ScopeContext systemScope(OutboxMessage message) {

        if (message.eventId() == null || message.ownerEntityId() == null || message.correlationId() == null) {
            throw new IllegalArgumentException("The envelope lacks its event id, owner entity or correlation id");
        }

        Scope active = new Scope(message.ownerEntityId(), message.locationId());

        return new ScopeContext(
                null,
                deviceOf(message),
                message.ownerEntityId(),
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                message.correlationId());
    }

    /**
     * The scope of counterparty delivery (CR-19A-13): OWN, entity-wide, of the payload's
     * counterparty, with no device. The first check is here: a till's event never reaches the
     * other party's books this way (K-08 events carry the device as their source).
     */
    private static ScopeContext counterpartyScope(OutboxMessage message, UUID counterparty, UUID documentId) {

        if (message.eventId() == null || message.ownerEntityId() == null || message.correlationId() == null) {
            throw new IllegalArgumentException("The envelope lacks its event id, owner entity or correlation id");
        }

        if (message.source() != null && !"central".equals(message.source())) {
            throw new IllegalArgumentException("Event " + message.eventId() + " was uploaded by a device ("
                    + message.source() + "); a device event is never delivered to a counterparty consumer");
        }

        if (counterparty.equals(message.ownerEntityId())) {
            throw new IllegalArgumentException(
                    "Event " + message.eventId() + " names its own owner as the counterparty of " + documentId);
        }

        Scope active = new Scope(counterparty, null);

        return new ScopeContext(
                null,
                null,
                counterparty,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                message.correlationId());
    }

    private static UUID uuidField(JsonNode fields, String name) {
        JsonNode value = fields.path(name);
        if (value.isMissingNode() || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return UUID.fromString(value.asText());
    }

    /**
     * K-08: an event a till uploaded has the device as its source (DeviceEventWriter); the
     * consumer that applies it sees the device on its scope, as the audit of what it does must.
     */
    private static java.util.UUID deviceOf(OutboxMessage message) {
        if (message.source() == null || "central".equals(message.source())) {
            return null;
        }
        try {
            return java.util.UUID.fromString(message.source());
        } catch (IllegalArgumentException notADevice) {
            return null;
        }
    }

    private static String errorText(RuntimeException failure) {

        String text = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());

        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    public enum DeliveryResult {
        APPLIED,
        DUPLICATE,
        RETRY,
        DEAD_LETTERED
    }
}
