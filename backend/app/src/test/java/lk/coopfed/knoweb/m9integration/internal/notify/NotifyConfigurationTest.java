package lk.coopfed.knoweb.m9integration.internal.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The start-up rule of the SMTP relay (wave 2, M9-11): plain SMTP to a relay beyond this machine
 * or network is refused outside development; a local or private relay, a development issuer, or
 * an encrypted mode is let through.
 */
class NotifyConfigurationTest {

    private static final String PRODUCTION_ISSUER = "https://id.coop-erp.lk/realms/coop";
    private static final String DEVELOPMENT_ISSUER = "http://localhost:8085/realms/coop";

    @Test
    void plainSmtpToAPublicRelayRefusesTheStartOutsideDevelopment() {
        assertThatThrownBy(() -> NotifyConfiguration.requireSafeRelay("smtp.example.com", "NONE", PRODUCTION_ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smtp.example.com")
                .hasMessageContaining("STARTTLS or SSL");
    }

    @Test
    void plainSmtpIsAllowedOnADevelopmentStackOrToALocalRelay() {
        assertThat(NotifyConfiguration.requireSafeRelay("smtp.example.com", "NONE", DEVELOPMENT_ISSUER))
                .isEqualTo(SmtpEmailChannel.Security.NONE);
        // The compose stack's service name, this machine, a development name, nothing configured.
        for (String local :
                new String[] {"mailpit", "localhost", "127.0.0.1", "mail.coop-erp.test", "relay.localhost", ""}) {
            assertThat(NotifyConfiguration.requireSafeRelay(local, "none", PRODUCTION_ISSUER))
                    .as(local)
                    .isEqualTo(SmtpEmailChannel.Security.NONE);
        }
    }

    @Test
    void anEncryptedModeIsAllowedAnywhereAndAnUnknownOneIsRefused() {
        assertThat(NotifyConfiguration.requireSafeRelay("smtp.example.com", "STARTTLS", PRODUCTION_ISSUER))
                .isEqualTo(SmtpEmailChannel.Security.STARTTLS);
        assertThat(NotifyConfiguration.requireSafeRelay("smtp.example.com", " ssl ", PRODUCTION_ISSUER))
                .isEqualTo(SmtpEmailChannel.Security.SSL);
        assertThatThrownBy(() -> NotifyConfiguration.requireSafeRelay("smtp.example.com", "TLS", PRODUCTION_ISSUER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NONE, STARTTLS or SSL");
    }
}
