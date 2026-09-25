package lk.coopfed.knoweb.kernel.api;

import java.util.Locale;
import java.util.UUID;

/**
 * The identity provider behind an interface with no provider type in it (AGENTS.md; doc 19
 * section 2.2; CR-19A-1). M1's user management calls this and nothing else: the provider
 * holds the credentials, the platform holds the users, and the scope rule of ADR-18 (the
 * caller's entity must be the target user's home entity, or its MPCS parent for a shop user)
 * is enforced by the implementation before any call reaches the provider, so that no
 * Federation role can reach the provider's raw administration API.
 *
 * <p>Declared by K-02; the implementation over the provider's administration API arrives with
 * the second K-02 pull request, together with device authentication.
 */
public interface IdentityProviderClient {

    /** Creates the login; returns the provider's subject id, which M1 stores on the user. */
    String createUser(UUID userId, String username, Locale language);

    void disableUser(String subjectId);

    /**
     * Generated on the provider's side and returned once, for delivery by a notification;
     * never logged (AGENTS.md).
     */
    TemporaryPassword setTemporaryPassword(String subjectId);

    void resetTotp(String subjectId);

    void revokeSessions(String subjectId);

    /** A one-time password; {@link #toString()} hides it, so a log line cannot carry it. */
    record TemporaryPassword(String value) {
        @Override
        public String toString() {
            return "TemporaryPassword[hidden]";
        }
    }
}
