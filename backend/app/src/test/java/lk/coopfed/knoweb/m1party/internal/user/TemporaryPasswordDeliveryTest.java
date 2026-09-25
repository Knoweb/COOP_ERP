package lk.coopfed.knoweb.m1party.internal.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.NotificationAudience;
import lk.coopfed.knoweb.kernel.api.NotificationAudience.Recipient;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.AudienceKind;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.NotificationRule;
import lk.coopfed.knoweb.kernel.api.Notifications;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** When a temporary password goes out by a notification, and when it is left to the caller. */
class TemporaryPasswordDeliveryTest {

    private static final UUID ENTITY = UUID.randomUUID();
    private final ScopeContext scope = ScopeContext.dev(UUID.randomUUID(), ENTITY, null);
    private final Notifications notifications = mock(Notifications.class);
    private final NotificationRuleQueries rules = mock(NotificationRuleQueries.class);
    private final NotificationAudience admins = mock(NotificationAudience.class);

    @Test
    void withoutM9NothingIsSent() {
        TemporaryPasswordDelivery delivery = new TemporaryPasswordDelivery(provider(null), List.of(), notifications);

        assertThat(delivery.deliver(ENTITY, "clerk", "Secret12", scope)).isFalse();
        verify(notifications, never()).send(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void withoutARuleNothingIsSent() {
        when(rules.activeRules(TemporaryPasswordDelivery.RULE_KEY, ENTITY)).thenReturn(List.of());
        TemporaryPasswordDelivery delivery =
                new TemporaryPasswordDelivery(provider(rules), List.of(admins), notifications);

        assertThat(delivery.deliver(ENTITY, "clerk", "Secret12", scope)).isFalse();
    }

    @Test
    void aRuleWhoseAudienceResolvesSendsToEveryRecipient() {
        when(rules.activeRules(TemporaryPasswordDelivery.RULE_KEY, ENTITY))
                .thenReturn(List.of(rule(AudienceKind.ROLE_AT_OWNER)));
        when(admins.kind()).thenReturn(AudienceKind.ROLE_AT_OWNER);
        when(admins.resolve(ENTITY, null, "entity-admin", List.of("SMS")))
                .thenReturn(List.of(
                        new Recipient("SMS", "+94770000001", "si"), new Recipient("SMS", "+94770000002", "ta")));
        TemporaryPasswordDelivery delivery =
                new TemporaryPasswordDelivery(provider(rules), List.of(admins), notifications);

        assertThat(delivery.deliver(ENTITY, "clerk", "Secret12", scope)).isTrue();
        verify(notifications)
                .send(
                        eq("SMS"),
                        eq("+94770000001"),
                        eq("si"),
                        eq("tpl-temp-password"),
                        eq(Map.of("username", "clerk", "temporaryPassword", "Secret12")),
                        any(),
                        eq(scope));
        verify(notifications)
                .send(eq("SMS"), eq("+94770000002"), eq("ta"), eq("tpl-temp-password"), any(), any(), eq(scope));
    }

    @Test
    void anExplicitAudienceHasNobodyToNameAndIsSkipped() {
        when(rules.activeRules(TemporaryPasswordDelivery.RULE_KEY, ENTITY))
                .thenReturn(List.of(rule(AudienceKind.EXPLICIT)));
        TemporaryPasswordDelivery delivery =
                new TemporaryPasswordDelivery(provider(rules), List.of(admins), notifications);

        assertThat(delivery.deliver(ENTITY, "clerk", "Secret12", scope)).isFalse();
        verify(notifications, never()).send(any(), any(), any(), any(), any(), any(), any());
    }

    private static NotificationRule rule(AudienceKind kind) {
        return new NotificationRule(
                UUID.randomUUID(),
                ENTITY,
                TemporaryPasswordDelivery.RULE_KEY,
                null,
                "tpl-temp-password",
                kind,
                "entity-admin",
                List.of("SMS"),
                1);
    }

    private static ObjectProvider<NotificationRuleQueries> provider(NotificationRuleQueries queries) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (queries != null) {
            beans.addBean("rules", queries);
        }
        return beans.getBeanProvider(NotificationRuleQueries.class);
    }
}
