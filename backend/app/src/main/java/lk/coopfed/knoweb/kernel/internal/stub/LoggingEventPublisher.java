package lk.coopfed.knoweb.kernel.internal.stub;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 17A stub of the event outbox: it writes a log line, not a row. 19A ticket K-05 replaces it
 * with the insert into kernel.event_outbox and the relay to the broker; callers do not change.
 *
 * <p>Strict on purpose:
 * <ul>
 *   <li>the event class has a {@code public static final String TYPE} in the dotted,
 *       versioned form {@code module.thing.happened.v1} (17A section 4.4); it becomes the
 *       routing key on the broker and can never be renamed once a consumer exists</li>
 *   <li>a transaction is open: an event must be recorded in the same transaction as the
 *       change it announces, or a crash between the two leaves them disagreeing</li>
 * </ul>
 */
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    private static final Pattern EVENT_TYPE = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)+\\.v[0-9]+");

    @Override
    public void publish(DomainEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("Cannot publish a null event");
        }
        String type = typeOf(event);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("events.publish(" + type + ") was called outside a transaction;"
                    + " call it inside the handler's @Transactional method, after audit.record");
        }

        log.info(
                "DOMAIN_EVENT_STUB type={} eventClass={}",
                type,
                event.getClass().getName());
    }

    /** Reads the TYPE constant every event class must declare. */
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
                        eventClass.getName() + ".TYPE must look like hello.greeting.registered.v1: " + type);
            }
            return type;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalArgumentException(eventClass.getName()
                    + " must declare: public static final String TYPE = \"module.thing.happened.v1\"");
        }
    }
}
