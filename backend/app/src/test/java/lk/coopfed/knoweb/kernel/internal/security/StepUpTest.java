package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The one freshness rule: the register's window for the caller's scope, twelve hours without one. */
class StepUpTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final UUID ENTITY = UUID.fromString("0190a900-0000-7000-8000-000000000001");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void withoutARegisterTheWindowIsTwelveHours() {
        StepUp stepUp = new StepUp(provider(null), clock);

        assertThat(stepUp.isFresh(scope(NOW.minus(Duration.ofHours(11))))).isTrue();
        assertThat(stepUp.isFresh(scope(NOW.minus(Duration.ofHours(13))))).isFalse();
        assertThat(stepUp.isFresh(scope(null))).isFalse();
        assertThat(stepUp.isFresh(null)).isFalse();
    }

    @Test
    void theRegisterNarrowsTheWindowForTheCallersScope() {
        ConfigRegistry register =
                registry((key, scope) -> StepUp.FRESHNESS_KEY.equals(key) && ENTITY.equals(scope.entityId())
                        ? Optional.of("PT10M")
                        : Optional.empty());
        StepUp stepUp = new StepUp(provider(register), clock);

        assertThat(stepUp.isFresh(scope(NOW.minus(Duration.ofMinutes(9))))).isTrue();
        assertThat(stepUp.isFresh(scope(NOW.minus(Duration.ofMinutes(11))))).isFalse();
    }

    @Test
    void aRegisterThatCannotBeReadNeverWidensTheWindow() {
        ConfigRegistry register = registry((key, scope) -> {
            throw new IllegalStateException("no scope on the connection");
        });
        StepUp stepUp = new StepUp(provider(register), clock);

        assertThat(stepUp.freshness(scope(NOW))).isEqualTo(StepUp.DEFAULT_FRESHNESS);
    }

    /** A register that answers reads only; a write is not what these tests are about. */
    private static ConfigRegistry registry(
            java.util.function.BiFunction<String, ScopeContext, Optional<String>> reads) {
        return new ConfigRegistry() {
            @Override
            public Optional<String> get(String key, ScopeContext scope) {
                return reads.apply(key, scope);
            }

            @Override
            public void set(String key, ConfigScope scope, String value, ScopeContext ctx, String reason) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ObjectProvider<ConfigRegistry> provider(ConfigRegistry register) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (register != null) {
            beans.addBean("register", register);
        }
        return beans.getBeanProvider(ConfigRegistry.class);
    }

    private static ScopeContext scope(Instant mfaAt) {
        Scope active = new Scope(ENTITY, null);
        return new ScopeContext(
                UUID.randomUUID(),
                null,
                ENTITY,
                List.of(active),
                active,
                PolicyClass.OWN,
                Set.of(),
                mfaAt,
                Locale.ENGLISH,
                Ids.next());
    }
}
