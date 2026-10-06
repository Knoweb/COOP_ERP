package lk.coopfed.knoweb.m9integration.internal.notify;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.NotificationChannel;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * SmtpEmailAdapter (29A section 6.3): JavaMail to the configured relay, the compose stack's
 * Mailpit locally ({@code coop-erp.integration.smtp.*}). Plain text in UTF-8, so a Sinhala or
 * Tamil body arrives as written. It returns the message id as the provider's reference, or
 * throws; the kernel retries with backoff and records the outcome (19A section 10).
 *
 * <p>Wave 2 (M9-09, M9-11; {@code docs/progress/deviations/2026-10-06-wave2-notifications.md}
 * (3)): the {@code Message-ID} is {@code <notificationId@domain of from>}, set before the send,
 * so a retry of a message the relay took but the kernel never recorded as SENT is the same mail
 * to the receiving side, which Gmail-class clients collapse. Spring's {@code JavaMailSenderImpl}
 * keeps an explicitly set id across {@code saveChanges()}. And the relay is reached as the
 * deployment says: {@link Security#STARTTLS} (required) or {@link Security#SSL}, both with the
 * server's identity checked, with a username and password when one is configured.
 *
 * <p>A failure is rethrown as the kernel's {@link SendFailed} with a category and without the
 * relay's own text, which can name the recipient: the kernel keeps the class and the category in
 * its log, and an address never sits in a log (ADR-27). The sender is built here rather than as
 * a Spring bean, so the mail health indicator does not make the application's health depend on
 * the relay.
 */
class SmtpEmailChannel implements NotificationChannel {

    /** How the relay is reached (coop-erp.integration.smtp.security). */
    enum Security {
        /** Plain SMTP: the local Mailpit, or a relay on the same private network. */
        NONE,
        /** SMTP with STARTTLS, required: the connection is upgraded or refused. */
        STARTTLS,
        /** SMTP over TLS from the first byte (SMTPS, port 465 by convention). */
        SSL
    }

    private final JavaMailSenderImpl sender;
    private final String from;
    private final String messageIdDomain;

    SmtpEmailChannel(
            String host,
            int port,
            String from,
            int timeoutMillis,
            String username,
            String password,
            Security security) {
        this.from = from;
        this.messageIdDomain = domainOf(from);
        this.sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (username != null && !username.isBlank()) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        sender.getJavaMailProperties().putAll(properties(timeoutMillis, username, security));
    }

    /**
     * The JavaMail properties of one mode, apart from the sender so a unit test reads them:
     * authentication when a username is given; for STARTTLS the upgrade enabled and required;
     * for SSL the connection encrypted from the start; in both the server's identity checked
     * against its certificate.
     */
    static Properties properties(int timeoutMillis, String username, Security security) {
        Properties properties = new Properties();
        properties.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMillis));
        properties.put("mail.smtp.timeout", String.valueOf(timeoutMillis));
        properties.put("mail.smtp.writetimeout", String.valueOf(timeoutMillis));
        properties.put("mail.smtp.auth", String.valueOf(username != null && !username.isBlank()));
        Security mode = security == null ? Security.NONE : security;
        switch (mode) {
            case STARTTLS -> {
                properties.put("mail.smtp.starttls.enable", "true");
                properties.put("mail.smtp.starttls.required", "true");
                properties.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case SSL -> {
                properties.put("mail.smtp.ssl.enable", "true");
                properties.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case NONE -> {
                // Plain SMTP; NotifyConfiguration refuses it for a public relay outside development.
            }
        }
        return properties;
    }

    /**
     * The right-hand side of the Message-ID: the domain of the from address, which a spam filter
     * expects there; the address's local part is never used.
     */
    static String domainOf(String from) {
        String address = from == null ? "" : from.strip();
        int open = address.indexOf('<');
        int close = address.indexOf('>');
        if (open >= 0 && close > open) {
            address = address.substring(open + 1, close);
        }
        int at = address.lastIndexOf('@');
        String domain = at >= 0 ? address.substring(at + 1).strip() : "";
        return domain.isEmpty() ? "coop-erp.invalid" : domain.toLowerCase(Locale.ROOT);
    }

    /** {@code <notificationId@domain>}: the same on every attempt to send one notification. */
    String messageId(UUID notificationId) {
        return "<" + notificationId + "@" + messageIdDomain + ">";
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
            message.setHeader("Message-ID", messageId(outgoing.notificationId()));
            sender.send(message);
            return message.getMessageID();
        } catch (MessagingException | MailException e) {
            SendFailed failed = new SendFailed(
                    categoryOf(e),
                    "The SMTP relay did not take notification " + outgoing.notificationId() + " ("
                            + e.getClass().getSimpleName() + ")");
            failed.initCause(e);
            throw failed;
        }
    }

    /**
     * The relay's answer as a category the kernel may keep: refused credentials, a recipient or
     * message refused, a connection that timed out, or anything else. Spring wraps JavaMail's
     * exceptions, so the causes are walked.
     */
    static FailureCategory categoryOf(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof AuthenticationFailedException || cause instanceof MailAuthenticationException) {
                return FailureCategory.AUTH;
            }
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.util.concurrent.TimeoutException) {
                return FailureCategory.TIMEOUT;
            }
            if (cause instanceof SendFailedException) {
                return FailureCategory.REJECTED;
            }
        }
        if (e instanceof MailSendException sendException) {
            for (Exception failed : sendException.getMessageExceptions()) {
                if (failed instanceof SendFailedException) {
                    return FailureCategory.REJECTED;
                }
            }
        }
        return FailureCategory.UNKNOWN;
    }
}
