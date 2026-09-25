package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Issues a user a new credential (21A section 6; doc 19 section 2.2).
 *
 * @param credential PASSWORD (a one-time temporary password at the identity provider),
 *                   SECOND_FACTOR (the user enrols a new one at the next sign-in) or PIN (the
 *                   till PIN, under the entity's PIN policy)
 * @param pin        the new PIN when {@code credential} is PIN; null otherwise
 */
public record ResetCredential(UUID userId, String credential, String pin) {

    public static final String PASSWORD = "PASSWORD";
    public static final String SECOND_FACTOR = "SECOND_FACTOR";
    public static final String PIN = "PIN";

    /** Never prints the PIN: a command can end up in a log line or an exception message. */
    @Override
    public String toString() {
        return "ResetCredential[userId=" + userId + ", credential=" + credential + ", pin="
                + (pin == null ? "none" : "hidden") + "]";
    }
}
