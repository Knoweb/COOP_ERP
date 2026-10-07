package lk.coopfed.knoweb.kernel.internal.notification;

import lk.coopfed.knoweb.kernel.api.KeyedHash;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What a recipient is stored as on {@code kernel.notification_log}: HMAC-SHA-256 of
 * {@code channel + ":" + recipient} under {@code coop-erp.notification.recipient-hash-key}
 * (wave 2, TWK-20 and M9-07; {@code docs/progress/deviations/2026-10-06-wave2-keyed-hashes.md}).
 * The unique key {@code (rule_id, event_id, recipient_hash)} and the hourly de-duplication
 * compare it; nothing reads it back.
 *
 * <p>Its own key, not {@code pending-key}: that one rotates (a sealed row lives a day), and every
 * rotation of this one breaks the de-duplication of what was sent before it. A rotation is a
 * maintenance-window act and may queue one duplicate for an event replayed across it. The key
 * id is stored beside the hash ({@code recipient_hash_key_id}; null on the rows kernel V0085
 * replaced with random values).
 */
@Component
class RecipientHash {

    static final String PROPERTY = "coop-erp.notification.recipient-hash-key";

    /** Development only: what the development key is derived from when nothing is configured. */
    static final String DEVELOPMENT_SEED = "coop-erp-notification-recipient-dev";

    private final KeyedHash hash;

    RecipientHash(
            @Value("${" + PROPERTY + ":}") String key, @Value("${coop-erp.security.oidc.issuer:}") String issuer) {
        this.hash = KeyedHash.fromProperty(
                PROPERTY + " (COOP_ERP_NOTIFICATION_RECIPIENT_KEY)", key, issuer, DEVELOPMENT_SEED);
    }

    /** The stored form of a recipient on a channel: 64 hex characters, never the recipient. */
    String of(String channel, String recipient) {
        return hash.hex(channel + ":" + recipient.strip());
    }

    /** Which key made the hashes written now. */
    String keyId() {
        return hash.keyId();
    }
}
