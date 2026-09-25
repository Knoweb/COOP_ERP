package lk.coopfed.knoweb.m1party.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * What {@link ResetCredential} answers.
 *
 * @param delivery          NOTIFIED when the temporary password went out by a notification
 *                          rule; RETURNED when it is in {@code temporaryPassword}, this once;
 *                          NONE for a second factor or a PIN, which have nothing to deliver
 * @param temporaryPassword the one-time password when {@code delivery} is RETURNED. It is left
 *                          out of JSON on purpose: the kernel stores a command's result for
 *                          idempotent replays, and a password must never be stored. So the
 *                          controller reads it from this record, and a replay of the same
 *                          request answers without it (issue a new reset instead).
 */
public record CredentialResetResult(
        UUID userId, String credential, String status, String delivery, @JsonIgnore String temporaryPassword) {

    public static final String NOTIFIED = "NOTIFIED";
    public static final String RETURNED = "RETURNED";
    public static final String NONE = "NONE";

    /** Never prints the password. */
    @Override
    public String toString() {
        return "CredentialResetResult[userId=" + userId + ", credential=" + credential + ", status=" + status
                + ", delivery=" + delivery + "]";
    }
}
