package lk.coopfed.knoweb.kernel.internal.stub;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.internal.KernelClockConfig;
import lk.coopfed.knoweb.kernel.internal.i18n.IcuMessages;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Spring can build the kernel beans that need no database, from their real constructors and
 * the real property names. The unit tests of these classes call the constructors by hand, so
 * they cannot see a bean Spring is unable to create (two constructors and no @Autowired, a
 * misspelt property); without this test only the integration tests would, and they need Docker.
 */
class KernelWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(KernelClockConfig.class, IcuMessages.class)
            .withPropertyValues("coop-erp.business-timezone=Asia/Colombo");

    @Test
    void theKernelBeansStart() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context.getBean(Messages.class).t("scope.required", Locale.ENGLISH))
                    .isEqualTo("Select the entity you are working for");
        });
    }
}
