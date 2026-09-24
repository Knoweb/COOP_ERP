package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnBean(BrokerAdapter.class)
public class EventConsumerDispatcher {

    static final String AUDIT_DEAD_LETTERED = "EVENT_CONSUMER_DEAD_LETTERED";

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
                .orElseThrow(() -> new IllegalArgumentException(
                        "No @EventConsumer registered for " + consumer + " / " + message.eventType()));

        ScopeContext scope = systemScope(message);

        try {

            Boolean applied = transaction.execute(status -> {
                applyScope(scope);

                return inbox.applyOnce(
                        consumer, message.eventId(), () -> registration.invoke(message.payload(), scope, mapper));
            });

            return Boolean.TRUE.equals(applied) ? DeliveryResult.APPLIED : DeliveryResult.DUPLICATE;

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

    private static ScopeContext systemScope(OutboxMessage message) {

        Scope active = new Scope(message.ownerEntityId(), message.locationId());

        return new ScopeContext(
                null,
                null,
                message.ownerEntityId(),
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                message.correlationId());
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
