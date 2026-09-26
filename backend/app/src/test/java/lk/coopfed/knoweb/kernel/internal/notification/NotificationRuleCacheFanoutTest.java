package lk.coopfed.knoweb.kernel.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The rule cache empties on every instance when M9 changes a rule: the dispatcher is a
 * {@code CacheFanoutListener} for {@code notification_rule.changed.v1} (the fan-out of #126), and
 * the timer stays as the backstop. The clock stands still here, so only the fan-out can empty it.
 */
class NotificationRuleCacheFanoutTest {

    private static final UUID ENTITY = UUID.fromString("0190a800-0000-7000-8000-00000000a001");

    private final ObjectMapper json = new ObjectMapper();
    private final NotificationRuleQueries queries = mock(NotificationRuleQueries.class);
    private final ScopeContext scope = SystemScope.own(ENTITY, null);
    private final NotificationDispatcher dispatcher = dispatcher();

    @Test
    void theDispatcherListensToTheRuleChangeOnEveryInstance() {
        assertThat(dispatcher.eventTypes()).containsExactly(NotificationDispatcher.RULE_CHANGED);
    }

    @Test
    void aRuleChangeHeardOnThisInstanceEmptiesTheCache() {
        dispatcher.onEvent(envelope("sale.completed.v1"), scope);
        dispatcher.onEvent(envelope("sale.completed.v1"), scope);
        verify(queries, times(1)).activeRules("sale.completed.v1", ENTITY);

        dispatcher.published(NotificationDispatcher.RULE_CHANGED, json.createObjectNode());
        dispatcher.onEvent(envelope("sale.completed.v1"), scope);

        verify(queries, times(2)).activeRules("sale.completed.v1", ENTITY);
    }

    @Test
    void anotherEventLeavesTheCacheAlone() {
        dispatcher.onEvent(envelope("sale.completed.v1"), scope);

        dispatcher.published("config.changed.v1", json.createObjectNode());
        dispatcher.onEvent(envelope("sale.completed.v1"), scope);

        verify(queries, times(1)).activeRules("sale.completed.v1", ENTITY);
    }

    private JsonNode envelope(String eventType) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventType", eventType);
        envelope.put("eventId", Ids.next().toString());
        envelope.putObject("payload");
        return envelope;
    }

    @SuppressWarnings("unchecked")
    private NotificationDispatcher dispatcher() {
        when(queries.activeRules(any(), any())).thenReturn(List.of());
        ObjectProvider<NotificationRuleQueries> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(queries);
        ConfigRegistry config = mock(ConfigRegistry.class);
        when(config.getInt(eq(NotificationDispatcher.CACHE_SECONDS), any(), anyInt()))
                .thenReturn(60);
        Clock still = Clock.fixed(Instant.parse("2026-09-27T08:00:00Z"), ZoneOffset.UTC);
        return new NotificationDispatcher(provider, List.of(), mock(NotificationService.class), json, config, still);
    }
}
