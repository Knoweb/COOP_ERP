package lk.coopfed.knoweb.kernel.api;

import java.util.Map;
import java.util.UUID;

/**
 * Notifications delivery (doc 19 section 7; 19A section 10). The ordinary way is the rule
 * driven one: a module publishes its domain event, M9's rules say who hears of it and how,
 * and the kernel's dispatcher sends. This interface is the direct way for the few cases that
 * are not an event of the business (a password reset code, a lockout notice): the caller
 * names the recipient and the template, inside its transaction, and the kernel logs, renders
 * in the recipient's language with English fallback, applies the suppressions and sends, with
 * the same retry as a rule-driven one.
 *
 * <p>The log keeps the fact and the length, never the body or the number (ADR-27).
 */
public interface Notifications {

    /**
     * @param channel     {@code SMS}, {@code EMAIL} or {@code IN_APP}
     * @param recipient   as the channel understands it; hashed before it is stored
     * @param language    {@code en}, {@code si} or {@code ta}; another language gets English
     * @param templateId  a template M9 holds, or a message id of the catalogue for the body
     * @param arguments   the placeholders of the template
     * @param dedupKey    a stable id for the thing notified (an event id, a document id): the same
     *                    key, recipient and template inside an hour sends once
     * @return the notification id and what was decided for it inside the caller's transaction.
     *         The provider is called only after that transaction commits, so a direct send never
     *         reports SENT at call time: QUEUED means logged for sending once the caller commits
     *         (a provider failure after that is the retry sweep's), SUPPRESSED that it will not be
     *         sent. 19A section 10 says send "returns the notification id"; see docs/PROGRESS.md.
     */
    Delivery send(
            String channel,
            String recipient,
            String language,
            String templateId,
            Map<String, Object> arguments,
            UUID dedupKey,
            ScopeContext ctx);

    /** What was decided for a direct send inside the caller's transaction. */
    enum Outcome {
        /** The channel accepted it (the log's status; a direct send reports QUEUED at call time). */
        SENT,
        /** Logged for sending: the provider is called after the caller commits, retried by the sweep. */
        QUEUED,
        /** Not sent, by a suppression: a repeat inside the hour, quiet hours, an opt-out or the kill switch. */
        SUPPRESSED,
        /** Nothing could be sent: every attempt failed, or nothing was logged for sending. */
        FAILED
    }

    record Delivery(UUID notificationId, Outcome outcome) {

        /**
         * True when the notification is sent or will be sent once the caller commits. A queued
         * send can still fail at the provider; the retry sweep handles that.
         */
        public boolean accepted() {
            return outcome == Outcome.SENT || outcome == Outcome.QUEUED;
        }
    }
}
