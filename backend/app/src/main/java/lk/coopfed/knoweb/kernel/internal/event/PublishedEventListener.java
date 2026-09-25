package lk.coopfed.knoweb.kernel.internal.event;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Told, after the publishing transaction has committed, of every event this instance wrote to
 * the outbox. For the caches that must not wait for the broker: the instance that changed a
 * role, a grant or a configuration value empties its own cache at once, and the other
 * instances learn it through the consumer runtime or the cache's expiry.
 */
public interface PublishedEventListener {

    /** The event type (dotted, versioned) and the payload as it was written. */
    void published(String eventType, JsonNode payload);
}
