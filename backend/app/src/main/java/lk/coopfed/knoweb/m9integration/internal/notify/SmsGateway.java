package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.UUID;

/**
 * One SMS provider behind the SMS channel and Central Notification Gateway (29A section 6.3). The
 * providers are open (doc 29 DR-4), so phase 1 has the log provider only; a real gateway is one
 * more implementation, chosen by {@code coop-erp.integration.sms.provider}, with its credentials
 * by reference in the environment, never in the repository.
 */
public interface SmsGateway {

    /** The name the configuration chooses it by. */
    String name();

    /**
     * @return the provider's reference, or throws for the kernel/gateway to retry. An exception's message
     *     must not carry the recipient or the body: the notification id is the idempotency key and
     *     all a failure needs to name (wave 2, M9-10).
     */
    String send(UUID notificationId, String phone, String body);
}
