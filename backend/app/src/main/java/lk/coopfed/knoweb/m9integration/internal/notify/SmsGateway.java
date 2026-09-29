package lk.coopfed.knoweb.m9integration.internal.notify;

import java.util.UUID;

/**
 * One SMS provider behind the SMS channel (29A section 6.3, "a small per-gateway template"). The
 * providers are open (doc 29 DR-4), so phase 1 has the log provider only; a real gateway is one
 * more implementation, chosen by {@code coop-erp.integration.sms.provider}, with its credentials
 * by reference in the environment, never in the repository.
 */
interface SmsGateway {

    /** The name the configuration chooses it by. */
    String name();

    /** @return the provider's reference, or throws for the kernel to retry */
    String send(UUID notificationId, String phone, String body);
}
