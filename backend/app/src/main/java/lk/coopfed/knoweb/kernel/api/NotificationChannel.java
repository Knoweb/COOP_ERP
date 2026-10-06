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

    /**
     * @return the provider's reference for the message, or null when the channel has none
     * @throws SendFailed (preferred) or any runtime exception when the provider did not take it
     */
    String send(Outgoing outgoing);

    /**
     * Why a provider did not take a message, in words the kernel may keep (wave 2, TWK-21 and
     * M9-10). The kernel stores the exception's class name and, for this one, the category, on
     * the delivery log and in the audit row; never an exception's message, which a provider can
     * fill with the recipient or the body. The adapter knows its provider's codes and maps them.
     */
    enum FailureCategory {
        /** The provider refused the message or the recipient. */
        REJECTED,
        /** The provider did not answer in time. */
        TIMEOUT,
        /** The provider refused our credentials. */
        AUTH,
        /** Anything else. */
        UNKNOWN
    }

    /**
     * The failure an adapter throws. Its message must carry neither the recipient nor the body
     * (the notification id is enough to find the message again): it goes to the application log
     * at DEBUG only.
     */
    class SendFailed extends RuntimeException {

        private final FailureCategory category;

        public SendFailed(FailureCategory category, String message) {
            super(message);
            this.category = category == null ? FailureCategory.UNKNOWN : category;
        }

        public FailureCategory category() {
            return category;
        }
    }
}
