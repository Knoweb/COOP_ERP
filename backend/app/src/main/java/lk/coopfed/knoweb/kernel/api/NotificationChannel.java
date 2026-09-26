package lk.coopfed.knoweb.kernel.api;

/**
 * A way to reach a person (doc 19 section 7: "SMS and e-mail in phase 1 through M9's provider
 * adapter interface; in-app is a third channel with no external provider"). M9's adapters
 * implement it, one bean per channel code; the kernel's dispatcher hands every rendered
 * notification to the adapter of its channel and records the outcome.
 *
 * <p>An adapter sends and returns the provider's reference, or throws: the kernel then retries
 * with backoff, three times inside 24 hours, and gives up with an ALERT (19A section 10). An
 * adapter never stores the body; the kernel keeps its length only.
 *
 * <p>A channel may have two adapters, a {@link Role#PRIMARY} and a {@link Role#SECONDARY}
 * provider (19A section 10: "secondary provider after the third"). The kernel sends through
 * the primary; after its third failed attempt it makes one more attempt through the
 * secondary, and only then gives up. Which provider is primary is M9's configuration
 * ({@code provider_config}); the kernel knows the role alone, never the provider.
 */
public interface NotificationChannel {

    /** {@code SMS}, {@code EMAIL} or {@code IN_APP}. */
    String channel();

    /**
     * What is sent: the recipient as the channel understands it (a phone number, an address,
     * a user id), the subject where the channel has one, the body, and the language it was
     * rendered in.
     */
    record Outgoing(java.util.UUID notificationId, String recipient, String subject, String body, String language) {}

    /** Which provider of the channel this adapter is. */
    enum Role {
        PRIMARY,
        SECONDARY
    }

    /** The primary unless the adapter says otherwise; at most one adapter per channel and role. */
    default Role role() {
        return Role.PRIMARY;
    }

    /** @return the provider's reference for the message, or null when the channel has none */
    String send(Outgoing outgoing);
}
