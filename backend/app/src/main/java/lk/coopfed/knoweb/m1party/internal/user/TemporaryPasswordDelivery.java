package lk.coopfed.knoweb.m1party.internal.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NotificationAudience;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.NotificationRule;
import lk.coopfed.knoweb.kernel.api.Notifications;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hands a temporary password to someone by a notification, when a rule says who (doc 19
 * section 2.2: the administrator issues a one-time temporary password; 19A section 10:
 * {@link Notifications#send} is the direct way "for a password reset code").
 *
 * <p>A password can never ride on a domain event (AGENTS.md), so the ordinary rule-driven path
 * cannot carry it. The rule is looked up here instead, under a key that is not an event type,
 * {@value #RULE_KEY}: M9 authors it like any other rule (a template with the placeholders
 * {@code username} and {@code temporaryPassword}, the channels, and an audience of a role
 * kind, for example the entity's administrators by SMS), and M1 sends it directly. M1 keeps no
 * telephone or e-mail of its users (doc 21 section 9.3), so an EXPLICIT audience has nobody to
 * name and is skipped. Without M9, or without a rule, or with a rule whose audience resolves
 * to nobody, nothing is sent and the caller returns the password once in the command's result.
 */
@Component
class TemporaryPasswordDelivery {

    static final String RULE_KEY = "user.temporary_password";

    private final ObjectProvider<NotificationRuleQueries> rules;
    private final List<NotificationAudience> audiences;
    private final Notifications notifications;

    TemporaryPasswordDelivery(
            ObjectProvider<NotificationRuleQueries> rules,
            List<NotificationAudience> audiences,
            Notifications notifications) {
        this.rules = rules;
        this.audiences = audiences;
        this.notifications = notifications;
    }

    /** True when the password went out to at least one recipient of a rule. */
    boolean deliver(UUID homeEntityId, String username, String temporaryPassword, ScopeContext scope) {
        NotificationRuleQueries queries = rules.getIfAvailable();
        if (queries == null) {
            return false;
        }
        for (NotificationRule rule : queries.activeRules(RULE_KEY, homeEntityId)) {
            List<NotificationAudience.Recipient> recipients = recipientsOf(rule, homeEntityId);
            if (recipients.isEmpty()) {
                continue;
            }
            // One key for this reset: a retry of the same notification is the kernel's, not a new send.
            UUID dedupKey = Ids.next();
            for (NotificationAudience.Recipient recipient : recipients) {
                notifications.send(
                        recipient.channel(),
                        recipient.recipient(),
                        recipient.language(),
                        rule.templateId(),
                        Map.of("username", username, "temporaryPassword", temporaryPassword),
                        dedupKey,
                        scope);
            }
            return true;
        }
        return false;
    }

    private List<NotificationAudience.Recipient> recipientsOf(NotificationRule rule, UUID homeEntityId) {
        List<NotificationAudience.Recipient> found = new ArrayList<>();
        if (rule.audienceKind() == NotificationRuleQueries.AudienceKind.EXPLICIT) {
            return found;
        }
        for (NotificationAudience audience : audiences) {
            if (audience.kind() == rule.audienceKind()) {
                found.addAll(audience.resolve(homeEntityId, null, rule.audienceSpec(), rule.channels()));
            }
        }
        return found;
    }
}
