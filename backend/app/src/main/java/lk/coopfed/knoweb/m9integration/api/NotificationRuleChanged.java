package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * notification_rule.changed.v1 (29A section 6): a rule was activated or retired. The kernel's
 * dispatcher empties its cache of active rules on it, on every instance (19A section 10).
 */
public record NotificationRuleChanged(UUID ruleId, String eventType, String status) implements DomainEvent {

    public static final String TYPE = "notification_rule.changed.v1";
}
