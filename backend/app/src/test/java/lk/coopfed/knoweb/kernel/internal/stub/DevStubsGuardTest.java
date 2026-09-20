package lk.coopfed.knoweb.kernel.internal.stub;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevStubsGuardTest {

    @Test
    void theStubsStartUnderTheRoleProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("web");

        assertThatCode(() -> new DevStubsGuard(environment)).doesNotThrowAnyException();
    }

    @Test
    void theStubsRefuseAProductionProfile() {
        for (String profile : new String[]{"prod", "production"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("web", profile);

            assertThatThrownBy(() -> new DevStubsGuard(environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start under a production profile");
        }
    }
}
