package lk.coopfed.knoweb.kernel.internal.event;

import java.util.Set;

/**
 * A per-instance cache that empties on events (doc 19 section 8: "cached per instance and
 * invalidated by config.changed.v1"; 19A section 3: the permission cache "invalidated by
 * role.changed.v1 ... fanned out over the broker"). Told twice: on the instance that made the
 * change the moment it commits ({@link PublishedEventListener}, from the outbox writer), and on
 * every instance when the event reaches its own fan-out queue
 * ({@link RabbitCacheFanoutRuntime}), whichever role it runs. The fan-out bypasses the shared
 * consumer queues and the inbox on purpose: those deliver an event to exactly one instance
 * once, and a cache lives on every instance.
 *
 * <p>Emptying a cache is idempotent, so a listener may hear the same event more than once and
 * must not mind; and it may miss one (the broker away): the cache's expiry bounds the
 * staleness, "within a minute" for the permission cache.
 */
public interface CacheFanoutListener extends PublishedEventListener {

    /** The event types (dotted, versioned) this cache empties on; the fan-out queue binds to each. */
    Set<String> eventTypes();
}
