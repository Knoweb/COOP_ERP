package lk.coopfed.knoweb.m9integration.internal.notify;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * SmtpEmailAdapter (29A section 6.3): JavaMail to the configured relay, the compose stack's
 * Mailpit locally ({@code coop-erp.integration.smtp.*}). Plain text in UTF-8, so a Sinhala or
 * Tamil body arrives as written. It returns the message id as the provider's reference, or
 * throws; the kernel retries with backoff and records the outcome (19A section 10).
 *
 * <p>A failure is rethrown without the relay's own text, which can name the recipient: the
 * kernel keeps the error in its log, and an address never sits in a log (ADR-27). The sender is
 * built here rather than as a Spring bean, so the mail health indicator does not make the
 * application's health depend on the relay.
 */
class SmtpEmailChannel implements NotificationChannel {

    private final JavaMailSenderImpl sender;
    private final String from;

    SmtpEmailChannel(String host, int port, String from, int timeoutMillis) {
        this.from = from;
        this.sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties properties = sender.getJavaMailProperties();
        properties.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMillis));
        properties.put("mail.smtp.timeout", String.valueOf(timeoutMillis));
        properties.put("mail.smtp.writetimeout", String.valueOf(timeoutMillis));
    }

    @Override
    public String channel() {
        return "EMAIL";
    }

    @Override
    public String send(Outgoing outgoing) {
        if (sender.getHost() == null || sender.getHost().isBlank()) {
            throw new IllegalStateException("No SMTP relay is configured (coop-erp.integration.smtp.host)");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(outgoing.recipient());
            helper.setSubject(outgoing.subject() == null ? "COOP ERP" : outgoing.subject());
            helper.setText(outgoing.body(), false);
            message.setHeader("X-Coop-Erp-Notification", String.valueOf(outgoing.notificationId()));
            sender.send(message);
            return message.getMessageID();
        } catch (MessagingException | MailException e) {
            throw new IllegalStateException("The SMTP relay did not take notification " + outgoing.notificationId()
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }
}
