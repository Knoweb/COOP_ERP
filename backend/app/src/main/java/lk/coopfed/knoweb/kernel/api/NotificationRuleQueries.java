package lk.coopfed.knoweb.kernel.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the kernel's dispatcher reads of M9 (doc 19 section 7: "rules are data owned by M9;
 * the kernel evaluates them as an ordinary event consumer"). M9 implements this over its
 * {@code notification_rule} and {@code notification_template} tables; until M9 exists no bean
 * implements it and the dispatcher matches nothing, while direct sends through
 * {@link Notifications} work.
 */
public interface NotificationRuleQueries {

    /** Who a rule addresses (doc 29 section 3). */
    enum AudienceKind {
        ROLE_AT_COUNTERPARTY,
        ROLE_AT_OWNER,
        CUSTOMER,
        EXPLICIT
    }

    /**
     * @param ownerEntityId  the entity whose rule it is; null for a federation-wide rule
     * @param predicate      a JSON object of payload field to required value, or null
     * @param audienceSpec   for EXPLICIT the payload field that holds the recipients (a string or
     *                       a list); for the role kinds the role code; for CUSTOMER the field that
     *                       holds the customer id
     */
    record NotificationRule(
            UUID ruleId,
            UUID ownerEntityId,
            String eventType,
            String predicate,
            String templateId,
            AudienceKind audienceKind,
            String audienceSpec,
            List<String> channels,
            int priority) {}

    /** The texts of a template in the three languages; English is mandatory (P-07). */
    record NotificationTemplate(
            String templateId,
            String channel,
            String subjectEn,
            String subjectSi,
            String subjectTa,
            String bodyEn,
            String bodySi,
            String bodyTa) {}

    /** The ACTIVE rules for an event type, federation-wide ones and the entity's, by priority. */
    List<NotificationRule> activeRules(String eventType, UUID ownerEntityId);

    Optional<NotificationTemplate> template(String templateId);
}
