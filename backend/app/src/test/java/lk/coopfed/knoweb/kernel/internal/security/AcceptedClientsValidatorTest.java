package lk.coopfed.knoweb.kernel.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** A token is accepted by the client it was issued to; the audience counts only without an azp. */
class AcceptedClientsValidatorTest {

    private final AcceptedClientsValidator validator = new AcceptedClientsValidator(List.of("coop-erp-web"), "device-");

    @Test
    void theWebClientAndATillAreAccepted() {
        assertThat(accepted(jwt -> jwt.claim("azp", "coop-erp-web"))).isTrue();
        assertThat(accepted(jwt -> jwt.claim("azp", "device-123"))).isTrue();
    }

    @Test
    void anotherClientIsRefusedEvenWithTheWebClientInTheAudience() {
        assertThat(accepted(jwt -> jwt.claim("azp", "admin-cli").audience(List.of("coop-erp-web"))))
                .isFalse();
    }

    @Test
    void withoutAnAzpTheAudienceDecides() {
        assertThat(accepted(jwt -> jwt.audience(List.of("coop-erp-web")))).isTrue();
        assertThat(accepted(jwt -> jwt.audience(List.of("account")))).isFalse();
        assertThat(accepted(jwt -> {})).isFalse();
    }

    private boolean accepted(Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("someone")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.accept(builder);
        return !validator.validate(builder.build()).hasErrors();
    }
}
