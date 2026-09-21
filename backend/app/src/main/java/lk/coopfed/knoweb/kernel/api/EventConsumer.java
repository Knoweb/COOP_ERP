package lk.coopfed.knoweb.kernel.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a consumer of domain events (19A section 5, "consumer framework with
 * inbox").
 *
 * <p>The framework gives each consumer its own queue, bound to the event types named here.
 * Before the annotated class sees a message the kernel inserts the event id into
 * {@code kernel.event_inbox} for this consumer name; when the row was already there the
 * message is acknowledged and skipped, which is what makes an at-least-once backbone
 * exactly-once for the consumer. The handler then runs in a transaction with a system
 * {@link ScopeContext} scoped to the event's entity, and a failure is retried; after three
 * failures the message goes to the dead-letter queue and an ALERT audit event is recorded.
 *
 * <pre>
 *   &#64;EventConsumer(types = {"grn.confirmed.v1"}, consumer = "m5.receipts")
 * </pre>
 *
 * <p>Declared here so that modules can be written against it; ticket K-05 builds the
 * framework that reads it. Nothing reads this annotation yet.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventConsumer {

    /**
     * The event types this consumer subscribes to, in the versioned form
     * {@code module.thing.happened.v1}. {@code "*"} subscribes to every type (19A section 10
     * uses it for the notification dispatcher).
     */
    String[] types();

    /**
     * The consumer's name: the key of its queue and of its {@code kernel.event_inbox} rows,
     * for example {@code m5.receipts}. It can never be renamed once the consumer has run,
     * because the inbox rows of the old name would no longer be found and every event would
     * be applied a second time.
     */
    String consumer();
}
