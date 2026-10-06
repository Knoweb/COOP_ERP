package lk.coopfed.knoweb.kernel.internal.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.NotificationAudience;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.AudienceKind;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.NotificationRule;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.event.CacheFanoutListener;
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
class NotificationDispatcher implements CacheFanoutListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    static final String CONSUMER = "kernel-notifications";

    private final ObjectProvider<NotificationRuleQueries> rules;
    private final Map<AudienceKind, NotificationAudience> audiences = new HashMap<>();
    private final NotificationService service;
    private final ObjectMapper json;
    private final ConfigRegistry config;
    private final Clock clock;

    /** M9 publishes it when a rule is created, changed, activated or retired. */
    static final String RULE_CHANGED = "notification_rule.changed.v1";

    static final String CACHE_SECONDS = "notification.rules.cache_seconds";

    private record RuleKey(String eventType, UUID entityId) {}

    private record CachedRules(List<NotificationRule> rules, Instant loadedAt) {}

    private final Map<RuleKey, CachedRules> cache = new ConcurrentHashMap<>();

    NotificationDispatcher(
            ObjectProvider<NotificationRuleQueries> rules,
            List<NotificationAudience> audienceBeans,
            NotificationService service,
            ObjectMapper json,
            ConfigRegistry config,
            Clock clock) {
        this.rules = rules;
        this.service = service;
        this.json = json;
        this.config = config;
        this.clock = clock;
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
        if (counterparty == null) {
            counterparty = otherParty(body, scope.entityId());
        }

        if (RULE_CHANGED.equals(eventType)) {
            // 19A section 10: the cache of active rules is invalidated by M9's change event.
            cache.clear();
        }

        for (NotificationRule rule : activeRules(queries, eventType, scope)) {
            if (!predicateHolds(rule.predicate(), body)) {
                continue;
            }
            for (NotificationAudience.Recipient recipient : audience(rule, body, scope.entityId(), counterparty)) {
                try {
                    service.deliver(rule.ruleId(), eventId, recipient, rule.templateId(), arguments(body), scope);
                } catch (ProblemException refused) {
                    // A rule that names a channel with no adapter, or an audience with a blank
                    // recipient, must not roll back the recipients before it (and be redelivered
                    // to them): the recipient is skipped and said in the log. The sends themselves
                    // run after the commit and never throw into the consumer.
                    log.warn(
                            "Rule {} on event {}: recipient on {} skipped: {}",
                            rule.ruleId(),
                            eventId,
                            recipient.channel(),
                            refused.messageId());
                }
            }
        }
    }

    /**
     * The active rules of an event type for the event's entity, cached (19A section 10). M9's
     * change event empties the cache on every instance through the per-instance fan-out
     * ({@link #published}); every entry also expires after
     * {@code notification.rules.cache_seconds}, the backstop for an instance the broker did not
     * reach.
     */
    private List<NotificationRule> activeRules(NotificationRuleQueries queries, String eventType, ScopeContext scope) {
        Instant now = clock.instant();
        long seconds = config.getInt(CACHE_SECONDS, scope, 60);
        RuleKey key = new RuleKey(eventType, scope.entityId());
        CachedRules cached = cache.get(key);
        if (cached != null && cached.loadedAt().plusSeconds(seconds).isAfter(now)) {
            return cached.rules();
        }
        List<NotificationRule> loaded = List.copyOf(queries.activeRules(eventType, scope.entityId()));
        if (seconds > 0) {
            cache.put(key, new CachedRules(loaded, now));
        }
        return loaded;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(RULE_CHANGED);
    }

    /**
     * M9's change event on this instance (CacheFanoutListener): the shared consumer queue hands
     * {@link #onEvent} to one instance only, and the rule cache lives on every instance.
     */
    @Override
    public void published(String eventType, JsonNode payload) {
        if (RULE_CHANGED.equals(eventType)) {
            cache.clear();
        }
    }

    /** Forgets every cached rule (the change event, and tests). */
    void invalidateRules() {
        cache.clear();
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

    /**
     * The counterparty of a trading event that names its two parties instead (M4's invoices,
     * receipts, cheques and exposure warnings carry sellerEntityId and buyerEntityId, no
     * counterpartyEntityId): the party that is not the event's owner. Added with M9's rules
     * (29 September 2026), whose ROLE_AT_COUNTERPARTY audience reaches the buyer of the seller's
     * invoice; null when the owner is neither party or the payload names neither.
     */
    static UUID otherParty(JsonNode body, UUID owner) {
        UUID seller = uuid(body.path("sellerEntityId").asText(null));
        UUID buyer = uuid(body.path("buyerEntityId").asText(null));
        if (owner == null || seller == null || buyer == null) {
            return null;
        }
        if (owner.equals(seller)) {
            return buyer;
        }
        return owner.equals(buyer) ? seller : null;
    }

    private static UUID uuid(String text) {
        try {
            return text == null ? null : UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
