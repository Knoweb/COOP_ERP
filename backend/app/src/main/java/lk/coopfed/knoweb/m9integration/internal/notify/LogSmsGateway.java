package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The development SMS provider: nothing leaves the machine. It writes one log line per message
 * with the notification id and the length of the text, never the number or the text (AGENTS.md:
 * no phone number in a log line), and answers a reference made of the id.
 */
class LogSmsGateway implements SmsGateway {

    static final String NAME = "log";

    private static final Logger log = LoggerFactory.getLogger(LogSmsGateway.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String send(UUID notificationId, String phone, String body) {
        log.info("SMS {} of {} characters taken by the log provider (not sent)", notificationId, body.length());
        return "log-" + notificationId;
    }
}
