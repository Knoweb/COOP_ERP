package lk.coopfed.knoweb.m9integration.internal.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;
import lk.coopfed.knoweb.kernel.api.NotificationChannel.FailureCategory;
import lk.coopfed.knoweb.m9integration.FakeSmtpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

/**
 * The SMTP adapter's modes and its Message-ID (wave 2, M9-09, M9-11): the JavaMail properties each
 * security mode builds; two attempts to send one notification carry one Message-ID; a failure is
 * the kernel's SendFailed with a category and without the relay's text.
 */
class SmtpEmailChannelTest {

    private static final UUID NOTIFICATION = UUID.fromString("0190e9e0-0000-7000-8000-000000000001");

    @Test
    void plainSmtpAuthenticatesOnlyWhenAUsernameIsGiven() {
        Properties anonymous = SmtpEmailChannel.properties(5000, "", SmtpEmailChannel.Security.NONE);
        assertThat(anonymous)
                .containsEntry("mail.smtp.auth", "false")
                .containsEntry("mail.smtp.connectiontimeout", "5000")
                .containsEntry("mail.smtp.timeout", "5000")
                .containsEntry("mail.smtp.writetimeout", "5000")
                .doesNotContainKeys("mail.smtp.starttls.enable", "mail.smtp.ssl.enable");

        Properties named = SmtpEmailChannel.properties(5000, "relay-user", SmtpEmailChannel.Security.NONE);
        assertThat(named).containsEntry("mail.smtp.auth", "true");
    }

    @Test
    void startTlsIsRequiredAndChecksTheServersIdentity() {
        Properties properties = SmtpEmailChannel.properties(5000, "relay-user", SmtpEmailChannel.Security.STARTTLS);
        assertThat(properties)
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .doesNotContainKey("mail.smtp.ssl.enable");
    }

    @Test
    void sslEncryptsFromTheFirstByteAndChecksTheServersIdentity() {
        Properties properties = SmtpEmailChannel.properties(5000, "relay-user", SmtpEmailChannel.Security.SSL);
        assertThat(properties)
                .containsEntry("mail.smtp.ssl.enable", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .doesNotContainKeys("mail.smtp.starttls.enable", "mail.smtp.starttls.required");
    }

    @Test
    void theMessageIdIsTheNotificationAtTheSendersDomain() {
        assertThat(SmtpEmailChannel.domainOf("notifications@coop-erp.test")).isEqualTo("coop-erp.test");
        assertThat(SmtpEmailChannel.domainOf("COOP ERP <Notifications@Mail.Coop-Erp.LK>"))
                .isEqualTo("mail.coop-erp.lk");
        assertThat(SmtpEmailChannel.domainOf("not-an-address")).isEqualTo("coop-erp.invalid");
        assertThat(SmtpEmailChannel.domainOf(null)).isEqualTo("coop-erp.invalid");

        SmtpEmailChannel channel = new SmtpEmailChannel(
                "127.0.0.1", 2525, "notifications@coop-erp.test", 1000, "", "", SmtpEmailChannel.Security.NONE);
        assertThat(channel.messageId(NOTIFICATION)).isEqualTo("<" + NOTIFICATION + "@coop-erp.test>");
    }

    @Test
    void twoAttemptsToSendOneNotificationCarryOneMessageId() throws Exception {
        try (FakeSmtpServer relay = new FakeSmtpServer()) {
            SmtpEmailChannel channel = new SmtpEmailChannel(
                    "127.0.0.1",
                    relay.port(),
                    "notifications@coop-erp.test",
                    5000,
                    "",
                    "",
                    SmtpEmailChannel.Security.NONE);
            NotificationChannel.Outgoing outgoing = new NotificationChannel.Outgoing(
                    NOTIFICATION, "accounts@buyer.coop-erp.test", "Invoice issued", "Rs 10.00", "en");

            String first = channel.send(outgoing);
            String second = channel.send(outgoing);

            assertThat(first).isEqualTo("<" + NOTIFICATION + "@coop-erp.test>").isEqualTo(second);
            assertThat(relay.messages).hasSize(2);
            for (String raw : relay.messages) {
                assertThat(parse(raw).getHeader("Message-ID")).containsExactly("<" + NOTIFICATION + "@coop-erp.test>");
                assertThat(parse(raw).getHeader("X-Coop-Erp-Notification")).containsExactly(NOTIFICATION.toString());
            }
        }
    }

    @Test
    void aRelayThatRefusesGivesTheKernelACategoryAndNoText() throws IOException {
        try (FakeSmtpServer relay = new FakeSmtpServer()) {
            relay.failNext.set(1);
            SmtpEmailChannel channel = new SmtpEmailChannel(
                    "127.0.0.1",
                    relay.port(),
                    "notifications@coop-erp.test",
                    5000,
                    "",
                    "",
                    SmtpEmailChannel.Security.NONE);
            NotificationChannel.Outgoing outgoing = new NotificationChannel.Outgoing(
                    NOTIFICATION, "accounts@buyer.coop-erp.test", "Invoice issued", "Rs 10.00", "en");

            assertThatThrownBy(() -> channel.send(outgoing))
                    .isInstanceOf(NotificationChannel.SendFailed.class)
                    .hasMessageContaining(NOTIFICATION.toString())
                    .hasMessageNotContaining("buyer.coop-erp.test")
                    .hasMessageNotContaining("try again later");
        }
    }

    @Test
    void theRelaysAnswerBecomesACategory() {
        assertThat(SmtpEmailChannel.categoryOf(new MailAuthenticationException("bad credentials")))
                .isEqualTo(FailureCategory.AUTH);
        assertThat(SmtpEmailChannel.categoryOf(
                        new MailSendException("x", new AuthenticationFailedException("535 5.7.8"))))
                .isEqualTo(FailureCategory.AUTH);
        assertThat(SmtpEmailChannel.categoryOf(
                        new MailSendException("x", new java.net.SocketTimeoutException("read timed out"))))
                .isEqualTo(FailureCategory.TIMEOUT);
        assertThat(SmtpEmailChannel.categoryOf(
                        new MailSendException("x", new jakarta.mail.SendFailedException("550 no such user"))))
                .isEqualTo(FailureCategory.REJECTED);
        assertThat(SmtpEmailChannel.categoryOf(new MailSendException("451 try again later")))
                .isEqualTo(FailureCategory.UNKNOWN);
    }

    private static MimeMessage parse(String raw) throws Exception {
        return new MimeMessage(
                Session.getInstance(new Properties()), new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
