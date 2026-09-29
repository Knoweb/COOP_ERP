package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.List;
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
            @Value("${coop-erp.integration.smtp.timeout-millis:5000}") int timeoutMillis) {
        return new SmtpEmailChannel(host, port, from, timeoutMillis);
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
