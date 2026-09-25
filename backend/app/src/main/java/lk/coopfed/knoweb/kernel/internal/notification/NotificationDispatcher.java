package lk.coopfed.knoweb.kernel.internal.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.NotificationAudience;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.AudienceKind;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.NotificationRule;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The rule-driven side of doc 19 section 7: an ordinary event consumer on every event type
 * that matches the event against M9's active rules, resolves the audience, and hands each
 * recipient to {@link NotificationService}. Without an M9 bean for the rules there is
 * nothing to match and the event passes. The consumer runs in the OWN scope of the event's
 * entity (the consumer framework gives it), so the log rows belong to that entity.
 *
 * <p>The predicate of a rule is a JSON object of payload field to required value, all of
 * which must hold. The EXPLICIT audience is the payload field the rule names, a string or a
 * list of strings; the other kinds are resolved by the {@link NotificationAudience} beans of
 * M1 and M7.
 */
@Component
class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    static final String CONSUMER = "kernel-notifications";

    private final ObjectProvider<NotificationRuleQueries> rules;
    private final Map<AudienceKind, NotificationAudience> audiences = new HashMap<>();
    private final NotificationService service;
    private final ObjectMapper json;

    NotificationDispatcher(
            ObjectProvider<NotificationRuleQueries> rules,
            List<NotificationAudience> audienceBeans,
            NotificationService service,
            ObjectMapper json) {
        this.rules = rules;
        this.service = service;
        this.json = json;
        audienceBeans.forEach(bean -> audiences.put(bean.kind(), bean));
    }

    @EventConsumer(types = "*", consumer = CONSUMER)
    public void onEvent(JsonNode payload, ScopeContext scope) {
        NotificationRuleQueries queries = rules.getIfAvailable();
        if (queries == null) {
            return;
        }

        String eventType = payload.path("eventType").asText(null);
        UUID eventId = uuid(payload.path("eventId").asText(null));
        if (eventType == null || eventId == null) {
            // The dispatcher is handed the envelope (type and id) with the payload; see EventConsumerDispatcher.
            log.debug("Event without a type or an id on its envelope: nothing to match");
            return;
        }

        JsonNode body = payload.path("payload");
        UUID counterparty = uuid(body.path("counterpartyEntityId").asText(null));

        for (NotificationRule rule : queries.activeRules(eventType, scope.entityId())) {
            if (!predicateHolds(rule.predicate(), body)) {
                continue;
            }
            for (NotificationAudience.Recipient recipient : audience(rule, body, scope.entityId(), counterparty)) {
                service.deliver(
                        rule.ruleId(),
                        eventId,
                        recipient.channel(),
                        recipient.recipient(),
                        recipient.language(),
                        rule.templateId(),
                        arguments(body),
                        scope);
            }
        }
    }

    private List<NotificationAudience.Recipient> audience(
            NotificationRule rule, JsonNode body, UUID ownerEntityId, UUID counterparty) {
        if (rule.audienceKind() == AudienceKind.EXPLICIT) {
            List<NotificationAudience.Recipient> recipients = new ArrayList<>();
            JsonNode field = body.path(rule.audienceSpec());
            String language = body.path("language").asText("en");
            if (field.isTextual()) {
                for (String channel : rule.channels()) {
                    recipients.add(new NotificationAudience.Recipient(channel, field.asText(), language));
                }
            } else if (field.isArray()) {
                for (JsonNode one : field) {
                    for (String channel : rule.channels()) {
                        recipients.add(new NotificationAudience.Recipient(channel, one.asText(), language));
                    }
                }
            }
            return recipients;
        }

        NotificationAudience resolver = audiences.get(rule.audienceKind());
        if (resolver == null) {
            log.warn("No audience resolver for {}: rule {} sends to nobody", rule.audienceKind(), rule.ruleId());
            return List.of();
        }
        return resolver.resolve(ownerEntityId, counterparty, rule.audienceSpec(), rule.channels());
    }

    private boolean predicateHolds(String predicate, JsonNode body) {
        if (predicate == null || predicate.isBlank()) {
            return true;
        }
        try {
            JsonNode conditions = json.readTree(predicate);
            Iterator<Map.Entry<String, JsonNode>> fields = conditions.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> condition = fields.next();
                if (!body.path(condition.getKey())
                        .asText("")
                        .equals(condition.getValue().asText())) {
                    return false;
                }
            }
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Rule predicate is not JSON: {}", predicate);
            return false;
        }
    }

    /** The payload's scalar fields as placeholders (doc 19: "placeholders from the event payload"). */
    private static Map<String, Object> arguments(JsonNode body) {
        Map<String, Object> arguments = new HashMap<>();
        body.fields().forEachRemaining(field -> {
            JsonNode value = field.getValue();
            if (value.isNumber()) {
                arguments.put(field.getKey(), value.numberValue());
            } else if (value.isValueNode()) {
                arguments.put(field.getKey(), value.asText());
            }
        });
        return arguments;
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
