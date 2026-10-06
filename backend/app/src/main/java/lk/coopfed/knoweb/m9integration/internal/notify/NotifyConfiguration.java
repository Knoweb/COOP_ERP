package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.List;
import java.util.Locale;
import lk.coopfed.knoweb.kernel.api.KeyedHash;
import lk.coopfed.knoweb.kernel.api.NotificationAudience;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.AudienceKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What M9 gives the kernel's notification delivery (19A section 10; 29A section 6.3): the rules
 * and templates, the audience of the two role kinds, and the adapters of the e-mail and SMS
 * channels. In-app notifications wait for the shell's bell (README).
 *
 * <p>{@code coop-erp.integration.notify.enabled} (on by default) turns all of it off together:
 * the kernel's own delivery test stands in test channels and a test rule store for M9, and a
 * second adapter per channel, or a second rule store, is a start-up error there.
 *
 * <p>SMTP (wave 2, M9-11): {@code coop-erp.integration.smtp.username}, {@code .password} (from
 * {@code COOP_ERP_SMTP_PASSWORD} only) and {@code .security} ({@code NONE}, {@code STARTTLS},
 * {@code SSL}). Invoice amounts go to accounts desks, so a plain relay is for a development
 * stack or a relay on the deployment's own network only: {@code NONE} with a host that is not
 * local refuses the start unless the OIDC issuer is a development one, the rule of {@code
 * PendingSeal} and {@code KeyedHash}.
 */
@Configuration
@ConditionalOnProperty(name = "coop-erp.integration.notify.enabled", havingValue = "true", matchIfMissing = true)
class NotifyConfiguration {

    @Bean
    NotificationRuleQueries integrationRuleStore(JdbcTemplate jdbc) {
        return new RuleStore(jdbc);
    }

    @Bean
    NotificationAudience integrationOwnerAudience(JdbcTemplate jdbc) {
        return new ContactAudience(AudienceKind.ROLE_AT_OWNER, jdbc);
    }

    @Bean
    NotificationAudience integrationCounterpartyAudience(JdbcTemplate jdbc) {
        return new ContactAudience(AudienceKind.ROLE_AT_COUNTERPARTY, jdbc);
    }

    @Bean
    NotificationChannel integrationEmailChannel(
            @Value("${coop-erp.integration.smtp.host:}") String host,
            @Value("${coop-erp.integration.smtp.port:1025}") int port,
            @Value("${coop-erp.integration.smtp.from:notifications@coop-erp.test}") String from,
            @Value("${coop-erp.integration.smtp.timeout-millis:5000}") int timeoutMillis,
            @Value("${coop-erp.integration.smtp.username:}") String username,
            @Value("${coop-erp.integration.smtp.password:}") String password,
            @Value("${coop-erp.integration.smtp.security:NONE}") String security,
            @Value("${coop-erp.security.oidc.issuer:}") String issuer) {
        SmtpEmailChannel.Security mode = requireSafeRelay(host, security, issuer);
        return new SmtpEmailChannel(host, port, from, timeoutMillis, username, password, mode);
    }

    /**
     * The security mode, refused when it would send invoice mail in clear to a relay beyond this
     * machine or network outside development. A relay is local when its host is this machine
     * (localhost, a loopback address), a development name ({@code .localhost}, {@code .test}), or
     * a bare service name with no dot, which only the deployment's own network resolves (the
     * compose stack's {@code mailpit}).
     */
    static SmtpEmailChannel.Security requireSafeRelay(String host, String security, String issuer) {
        SmtpEmailChannel.Security mode;
        try {
            mode = SmtpEmailChannel.Security.valueOf(
                    (security == null ? "NONE" : security.strip()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException(
                    "coop-erp.integration.smtp.security must be NONE, STARTTLS or SSL, not \"" + security + "\"");
        }
        if (mode == SmtpEmailChannel.Security.NONE && !isLocalRelay(host) && !KeyedHash.isDevelopmentIssuer(issuer)) {
            throw new IllegalStateException("coop-erp.integration.smtp.security is NONE and the relay " + host
                    + " is not on this machine or network: mail would go in clear."
                    + " Set COOP_ERP_SMTP_SECURITY to STARTTLS or SSL outside development.");
        }
        return mode;
    }

    static boolean isLocalRelay(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String name = host.strip().toLowerCase(Locale.ROOT);
        return name.equals("localhost")
                || name.equals("127.0.0.1")
                || name.equals("::1")
                || name.equals("[::1]")
                || name.endsWith(".localhost")
                || name.endsWith(".test")
                || name.indexOf('.') < 0 && name.indexOf(':') < 0;
    }

    @Bean
    SmsGateway integrationLogSmsGateway() {
        return new LogSmsGateway();
    }

    @Bean
    NotificationChannel integrationSmsChannel(
            List<SmsGateway> gateways,
            @Value("${coop-erp.integration.sms.provider:" + LogSmsGateway.NAME + "}") String provider) {
        return new SmsChannel(gateways, provider);
    }
}
