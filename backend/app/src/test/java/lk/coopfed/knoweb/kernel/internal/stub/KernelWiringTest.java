package lk.coopfed.knoweb.kernel.internal.stub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lk.coopfed.knoweb.kernel.api.BusinessDate;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.internal.KernelClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring can build the kernel beans that need no database, from their real constructors and
 * the real property names. The unit tests of these classes call the constructors by hand, so
 * they cannot see a bean Spring is unable to create (two constructors and no @Autowired, a
 * misspelt property); without this test only the integration tests would, and they need Docker.
 */
class KernelWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(
                    KernelClockConfig.class,
                    JsonMessages.class,
                    CalendarBusinessDate.class,
                    SeedConfigRegistry.class,
                    DevStubsGuard.class)
            .withPropertyValues("coop-erp.business-timezone=Asia/Colombo");

    @Test
    void theKernelBeansStart() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context.getBean(Messages.class).t("scope.required", Locale.ENGLISH))
                    .isEqualTo("Select the entity you are working for");
            assertThat(context.getBean(BusinessDate.class).current(UUID.randomUUID())).isNotNull();
            assertThat(context.getBean(ConfigRegistry.class).get("business.timezone", null)).contains("Asia/Colombo");
        });
    }

    @Test
    void theStubsRefuseToStartUnderAProductionProfile() {
        runner.withPropertyValues("spring.profiles.active=production").run(context ->
                assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("Refusing to start under a production profile"));
    }

    @Test
    void theBusinessTimeZoneIsNotOptional() {
        new ApplicationContextRunner()
                .withUserConfiguration(KernelClockConfig.class, CalendarBusinessDate.class)
                .run(context -> assertThat(context).hasFailed());
    }
}
