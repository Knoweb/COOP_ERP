package lk.coopfed.knoweb.kernel.api;

import java.util.List;
import java.util.UUID;

/**
 * Resolves who is behind a rule's audience (doc 19 section 7: "resolved at send time from M1:
 * named roles at the counterparty entity, the customer's phone, or an explicit recipient on
 * the event"). M1 implements the role kinds over its users and roles, M7 the CUSTOMER kind
 * with the STATEMENTS consent; the kernel resolves EXPLICIT itself from the payload. With no
 * bean for a kind, that audience is empty and the rule sends to nobody.
 */
public interface NotificationAudience {

    /**
     * One person to reach on one channel: the recipient as the channel understands it, and the
     * language. {@code entityId} and {@code roleCode} say who the recipient is without saying the
     * number (wave 2, CR-19A-12): the entity whose contact it is (ROLE_AT_COUNTERPARTY gives the
     * counterparty) and the role it was reached as. The kernel keeps both on the delivery log, so
     * the log screen never needs a hash, and reads that entity's quiet hours. Null when the
     * resolver does not know them (EXPLICIT, a direct send): then the event owner's are read.
     */
    record Recipient(String channel, String recipient, String language, UUID entityId, String roleCode) {

        /** A recipient whose entity and role are not known. */
        public Recipient(String channel, String recipient, String language) {
            this(channel, recipient, language, null, null);
        }
    }

    /** The kind this bean resolves. */
    NotificationRuleQueries.AudienceKind kind();

    /**
     * @param ownerEntityId        the entity the event belongs to
     * @param counterpartyEntityId the counterparty on the event's payload, when it has one
     * @param spec                 the rule's audience spec (a role code, a field name)
     * @param channels             the channels the rule sends on
     */
    List<Recipient> resolve(UUID ownerEntityId, UUID counterpartyEntityId, String spec, List<String> channels);
}
