package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lk.coopfed.knoweb.kernel.api.NotificationChannel.FailureCategory;
import lk.coopfed.knoweb.kernel.api.NotificationChannel.SendFailed;

/**
 * Configurable mock SMS gateway for testing provider abstraction, failover, and retry mechanics (PGL-M3-02).
 */
public class MockSmsGateway implements SmsGateway {

    public static final String NAME = "mock";

    public record SentMessage(UUID notificationId, String phone, String body) {}

    private final List<SentMessage> sentMessages = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger failNextAttempts = new AtomicInteger(0);
    private volatile FailureCategory failureCategory = FailureCategory.TIMEOUT;

    @Override
    public String name() {
        return NAME;
    }

    public void failNext(int count) {
        failNextAttempts.set(count);
    }

    public void setFailureCategory(FailureCategory category) {
        this.failureCategory = category;
    }

    public List<SentMessage> sentMessages() {
        return List.copyOf(sentMessages);
    }

    public void clear() {
        sentMessages.clear();
        failNextAttempts.set(0);
    }

    @Override
    public String send(UUID notificationId, String phone, String body) {
        if (failNextAttempts.getAndDecrement() > 0) {
            throw new SendFailed(failureCategory, "Simulated SMS provider failure for " + notificationId);
        }
        sentMessages.add(new SentMessage(notificationId, phone, body));
        return "mock-" + notificationId;
    }
}
