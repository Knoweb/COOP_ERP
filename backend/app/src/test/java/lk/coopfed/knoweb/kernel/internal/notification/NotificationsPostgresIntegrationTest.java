package lk.coopfed.knoweb.kernel.internal.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry.ConfigScope;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import lk.coopfed.knoweb.kernel.api.Notifications;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivery against PostgreSQL with a test channel and a test rule store standing in for M9
 * (19A section 10, "Tests"): a replayed event sends once; quiet-hours suppression; the kill
 * switch; fallback language; a failing provider is retried by the sweep and given up with
 * an ALERT; the dispatcher matches an event against a rule and its explicit audience; the
 * log holds a hash and a length, never the number or the body.
 */
@Import(NotificationsPostgresIntegrationTest.TestBeans.class)
class NotificationsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ENTITY = UUID.fromString("0190c100-0000-7000-8000-000000000001");
    private static final UUID FEDERATION = UUID.fromString("0190c100-0000-7000-8000-0000000000f1");
    private static final UUID USER = UUID.fromString("0190c100-0000-7000-8000-000000000010");
    private static final UUID RULE = UUID.fromString("0190c100-0000-7000-8000-0000000000a1");

    @DynamicPropertySource
    static void systemEntity(DynamicPropertyRegistry registry) {
        registry.add("coop-erp.system.entity-id", FEDERATION::toString);
    }

    @Autowired
    Notifications notifications;

    @Autowired
    ConfigRegistry config;

    @Autowired
    RetrySweepJob sweep;

    @Autowired
    NotificationDispatcher dispatcher;

    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper json;

    @Autowired
    TestBeans.TestChannel sms;

    @Autowired
    TestBeans.TestRules rules;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("delete from kernel.notification_log");
        admin.execute("delete from kernel.event_inbox");
        admin.execute("delete from kernel.config_value");
        admin.execute("truncate table kernel.audit_event");
        sms.sent.clear();
        sms.failNext = 0;
        sms.refs = 0;
        rules.rules.clear();
        ((lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry) config).invalidate("notification.sms.enabled");
        ((lk.coopfed.knoweb.kernel.internal.config.JdbcConfigRegistry) config)
                .invalidate("notification.sms.quiet_hours");
        // No quiet hours in this test unless a case sets them: the clock is whatever it is.
        inScope(ENTITY, () -> {
            config.set("notification.sms.quiet_hours", ConfigScope.entity(ENTITY), "", scope(ENTITY), "test");
            return null;
        });
    }

    @Test
    void aDirectSendIsRenderedInTheRecipientsLanguageAndLoggedWithoutTheNumber() {
        Notifications.Delivery delivery = inScope(
                ENTITY,
                () -> notifications.send(
                        "SMS", "0771234567", "si", "hello.greeting.duplicate", Map.of(), Ids.next(), scope(ENTITY)));
        UUID id = delivery.notificationId();
        assertThat(delivery.outcome()).isEqualTo(Notifications.Outcome.SENT);
        assertThat(delivery.reached()).isTrue();

        assertThat(sms.sent).hasSize(1);
        assertThat(sms.sent.get(0).recipient()).isEqualTo("0771234567");
        assertThat(sms.sent.get(0).language()).isEqualTo("si");
        assertThat(sms.sent.get(0).body()).isNotBlank();

        Map<String, Object> row = superuserJdbc()
                .queryForMap(
                        "select status, recipient_hash, rendered_length, language, provider_ref from kernel.notification_log"
                                + " where notification_id = ?",
                        id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(String.valueOf(row.get("recipient_hash"))).hasSize(64).doesNotContain("0771234567");
        assertThat(row.get("rendered_length")).isEqualTo(sms.sent.get(0).body().length());
        assertThat(row.get("provider_ref")).isEqualTo("ref-1");
    }

    @Test
    void aLanguageWithoutTheTextFallsBackToEnglish() {
        inScope(
                ENTITY,
                () -> notifications.send(
                        "SMS", "0771234567", "de", "hello.greeting.duplicate", Map.of(), Ids.next(), scope(ENTITY)));
        assertThat(sms.sent.get(0).language()).isEqualTo("en");
    }

    @Test
    void theSameThingToTheSamePersonSendsOnce() {
        UUID event = Ids.next();
        inScope(
                ENTITY,
                () -> notifications.send(
                        "SMS", "0771234567", "en", "hello.greeting.duplicate", Map.of(), event, scope(ENTITY)));
        inScope(
                ENTITY,
                () -> notifications.send(
                        "SMS", "0771234567", "en", "hello.greeting.duplicate", Map.of(), event, scope(ENTITY)));
        assertThat(sms.sent).hasSize(1);

        // A different event, same template and person inside the hour: de-duplicated, logged as such.
        Notifications.Delivery repeat = inScope(
                ENTITY,
                () -> notifications.send(
                        "SMS", "0771234567", "en", "hello.greeting.duplicate", Map.of(), Ids.next(), scope(ENTITY)));
        UUID other = repeat.notificationId();
        assertThat(repeat.outcome()).isEqualTo(Notifications.Outcome.SUPPRESSED);
        assertThat(repeat.reached()).isFalse();
        assertThat(sms.sent).hasSize(1);
        assertThat(superuserJdbc()
                        .queryForMap(
                                "select status, suppressed_reason from kernel.notification_log where notification_id = ?",
                                other))
                .containsEntry("status", "SUPPRESSED")
                .containsEntry("suppressed_reason", "DUPLICATE_WITHIN_HOUR");
    }

    @Test
    void theKillSwitchAndTheQuietHoursSuppress() {
        inScope(ENTITY, () -> {
            config.set("notification.sms.enabled", ConfigScope.entity(ENTITY), "false", scope(ENTITY), "test");
            return null;
        });
        UUID off = inScope(ENTITY, () -> notifications
                .send("SMS", "0771234567", "en", "hello.greeting.duplicate", Map.of(), Ids.next(), scope(ENTITY))
                .notificationId());
        assertThat(sms.sent).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select suppressed_reason from kernel.notification_log where notification_id = ?",
                                String.class,
                                off))
                .isEqualTo("KILL_SWITCH");

        inScope(ENTITY, () -> {
            config.set("notification.sms.enabled", ConfigScope.entity(ENTITY), "true", scope(ENTITY), "test");
            // The whole day is quiet: whatever the clock says, it is inside the window.
            config.set(
                    "notification.sms.quiet_hours", ConfigScope.entity(ENTITY), "00:00-23:59", scope(ENTITY), "test");
            return null;
        });
        UUID quiet = inScope(ENTITY, () -> notifications
                .send("SMS", "0777654321", "en", "hello.greeting.duplicate", Map.of(), Ids.next(), scope(ENTITY))
                .notificationId());
        assertThat(sms.sent).isEmpty();
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select suppressed_reason from kernel.notification_log where notification_id = ?",
                                String.class,
                                quiet))
                .isEqualTo("QUIET_HOURS");
    }

    @Test
    void aFailingProviderIsRetriedBySweepAndGivenUpWithAnAlert() {
        sms.failNext = 5;
        Notifications.Delivery delivery = inScope(
                ENTITY,
                () -> notifications.send(
                        "SMS", "0771234567", "en", "hello.greeting.duplicate", Map.of(), Ids.next(), scope(ENTITY)));
        UUID id = delivery.notificationId();
        // A retry is not a delivery: the caller of a one-time password must not count on it.
        assertThat(delivery.outcome()).isEqualTo(Notifications.Outcome.QUEUED);
        assertThat(delivery.reached()).isFalse();

        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(attempts(id)).isEqualTo(1);

        // Not due yet (the back-off): the sweep leaves it.
        assertThat(sweep.retryDue()).isZero();

        // Make it due, twice: the second and the third attempt, then FAILED with the ALERT.
        makeDue(id);
        assertThat(sweep.retryDue()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("QUEUED");
        makeDue(id);
        assertThat(sweep.retryDue()).isEqualTo(1);
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(attempts(id)).isEqualTo(3);
        assertThat(superuserJdbc()
                        .queryForObject(
                                "select count(*) from kernel.audit_event where event_type_code = 'NOTIFICATION_FAILED'",
                                Long.class))
                .isEqualTo(1L);
        assertThat(sweep.retryDue()).isZero();
    }

    @Test
    void theDispatcherMatchesAnEventAgainstARuleAndItsExplicitAudience() {
        rules.rules.add(new NotificationRuleQueries.NotificationRule(
                RULE,
                null,
                "hello.greeting.registered.v1",
                "{\"status\":\"REGISTERED\"}",
                "greeting-registered",
                NotificationRuleQueries.AudienceKind.EXPLICIT,
                "notify",
                List.of("SMS"),
                100));

        // The envelope the consumer framework hands a consumer of every type (EventConsumerDispatcher).
        UUID eventId = Ids.next();
        String envelope = "{\"eventType\":\"hello.greeting.registered.v1\",\"eventId\":\"" + eventId + "\","
                + "\"ownerEntityId\":\"" + ENTITY + "\",\"payload\":{\"status\":\"REGISTERED\","
                + "\"notify\":[\"0771111111\",\"0772222222\"],\"language\":\"ta\",\"textEn\":\"Hi\"}}";

        inScope(ENTITY, () -> {
            try {
                dispatcher.onEvent(json.readTree(envelope), scope(ENTITY));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        assertThat(sms.sent)
                .extracting(NotificationChannel.Outgoing::recipient)
                .containsExactly("0771111111", "0772222222");
        assertThat(sms.sent.get(0).language()).isEqualTo("ta");
        assertThat(sms.sent.get(0).body()).contains("Hi");

        // The same event again (a replay past the inbox): the log's unique key says once.
        inScope(ENTITY, () -> {
            try {
                dispatcher.onEvent(json.readTree(envelope), scope(ENTITY));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        assertThat(sms.sent).hasSize(2);

        // A predicate that does not hold: nothing.
        String other = "{\"eventType\":\"hello.greeting.registered.v1\",\"eventId\":\"" + Ids.next() + "\","
                + "\"ownerEntityId\":\"" + ENTITY + "\",\"payload\":{\"status\":\"DRAFT\",\"notify\":\"0773333333\"}}";
        inScope(ENTITY, () -> {
            try {
                dispatcher.onEvent(json.readTree(other), scope(ENTITY));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        assertThat(sms.sent).hasSize(2);
    }

    @Test
    void anUnknownChannelOrNoRecipientIsRefused() {
        assertThatThrownBy(() -> inScope(
                        ENTITY,
                        () -> notifications.send(
                                "PIGEON", "x", "en", "hello.greeting.duplicate", Map.of(), Ids.next(), scope(ENTITY))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("notification.channel_unknown");
        assertThatThrownBy(() -> inScope(
                        ENTITY,
                        () -> notifications.send(
                                "SMS", " ", "en", "hello.greeting.duplicate", Map.of(), Ids.next(), scope(ENTITY))))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("notification.recipient_required");
    }

    private String status(UUID id) {
        return superuserJdbc()
                .queryForObject(
                        "select status from kernel.notification_log where notification_id = ?", String.class, id);
    }

    private int attempts(UUID id) {
        return superuserJdbc()
                .queryForObject(
                        "select attempts from kernel.notification_log where notification_id = ?", Integer.class, id);
    }

    private void makeDue(UUID id) {
        superuserJdbc()
                .update(
                        "update kernel.notification_log set next_attempt_at = now() - interval '1 minute' where notification_id = ?",
                        id);
    }

    private static ScopeContext scope(UUID entity) {
        Scope active = new Scope(entity, null);
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
    }

    private <T> T inScope(UUID entity, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.queryForList(
                    "select set_config('app.user_id', ?, true), set_config('app.correlation_id', ?, true),"
                            + " set_config('app.scope_entity_id', ?, true), set_config('app.scope_location_id', '', true),"
                            + " set_config('app.scope_class', 'OWN', true), set_config('app.granted_entities', '{}', true)",
                    USER.toString(),
                    Ids.next().toString(),
                    entity.toString());
            return work.get();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        /** An SMS adapter that remembers what it sent and can be told to fail. */
        static class TestChannel implements NotificationChannel {
            final List<Outgoing> sent = new CopyOnWriteArrayList<>();
            volatile int failNext;
            volatile int refs;

            @Override
            public String channel() {
                return "SMS";
            }

            @Override
            public String send(Outgoing outgoing) {
                if (failNext > 0) {
                    failNext--;
                    throw new IllegalStateException("gateway down");
                }
                sent.add(outgoing);
                return "ref-" + (++refs);
            }
        }

        /** M9's rules and templates, in memory. */
        static class TestRules implements NotificationRuleQueries {
            final List<NotificationRule> rules = new ArrayList<>();

            @Override
            public List<NotificationRule> activeRules(String eventType, UUID ownerEntityId) {
                return rules.stream()
                        .filter(rule -> rule.eventType().equals(eventType))
                        .toList();
            }

            @Override
            public Optional<NotificationTemplate> template(String templateId) {
                if (!"greeting-registered".equals(templateId)) {
                    return Optional.empty();
                }
                return Optional.of(new NotificationTemplate(
                        templateId,
                        "SMS",
                        null,
                        null,
                        null,
                        "Greeting {textEn} registered",
                        "සුබපැතුම {textEn} ලියාපදිංචි විය",
                        "வாழ்த்து {textEn} பதிவு செய்யப்பட்டது"));
            }
        }

        @Bean
        TestChannel testSmsChannel() {
            return new TestChannel();
        }

        @Bean
        TestRules testRules() {
            return new TestRules();
        }
    }
}
