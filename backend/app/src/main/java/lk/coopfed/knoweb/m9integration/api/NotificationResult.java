package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;

/**
 * Result of a notification delivery or retry attempt from the Central Notification Gateway (PGL-M3-02).
 *
 * @param requestId   identifier of the processed request
 * @param status      current lifecycle state (PENDING, SENT, FAILED)
 * @param providerRef external provider receipt or message reference
 * @param error       failure description or category (scrubbed of PII)
 * @param duplicate   true if an earlier completed notification satisfied the request idempotently
 */
public record NotificationResult(UUID requestId, String status, String providerRef, String error, boolean duplicate) {

    public boolean isSuccess() {
        return "SENT".equalsIgnoreCase(status);
    }
}
