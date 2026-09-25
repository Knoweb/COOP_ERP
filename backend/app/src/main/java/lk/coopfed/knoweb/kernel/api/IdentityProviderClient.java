package lk.coopfed.knoweb.kernel.api;

import java.util.Locale;
import java.util.UUID;

/**
 * The identity provider behind an interface with no provider type in it (AGENTS.md; doc 19
 * section 2.2; CR-19A-1). M1's user management calls this and nothing else: the provider
 * holds the credentials, the platform holds the users. The scope rule of ADR-18 is enforced
 * by the implementation before any call reaches the provider: the caller's active entity
 * must be the target user's home entity (an MPCS administers its shops' users, since a shop
 * is a location of the MPCS and not an entity), so that no Federation role can reach the
 * provider's raw administration API. A call outside the rule is refused with
 * {@code identity.scope}; a provider that will not answer is {@code identity.unavailable}.
 *
 * <p>Every method takes the caller's {@link ScopeContext}, as every handler does: the rule
 * is checked against it, and the provider is never called on nobody's behalf. (CR-19A-1
 * proposed the signatures without it; the kernel's convention adds it.)
 */
public interface IdentityProviderClient {

    /**
     * Creates the login for a platform user and returns the provider's subject id, which M1
     * stores on the user ({@code app_user.provider_subject}). The provider carries the
     * platform's user id as the {@code uid} attribute, so its tokens name the same user.
     */
    String createUser(ScopeContext ctx, UUID userId, UUID homeEntityId, String username, Locale language);

    void disableUser(ScopeContext ctx, String subjectId);

    /**
     * A one-time password the user must change at the next sign-in; generated here, returned
     * once for delivery by a notification, never logged (AGENTS.md).
     */
    TemporaryPassword setTemporaryPassword(ScopeContext ctx, String subjectId);

    /** Removes the second factor; the user enrols a new one at the next sign-in. */
    void resetTotp(ScopeContext ctx, String subjectId);

    /** Ends every session of the user at the provider: the refresh tokens stop working. */
    void revokeSessions(ScopeContext ctx, String subjectId);

    /** A one-time password; {@link #toString()} hides it, so a log line cannot carry it. */
    record TemporaryPassword(String value) {
        @Override
        public String toString() {
            return "TemporaryPassword[hidden]";
        }
    }
}
