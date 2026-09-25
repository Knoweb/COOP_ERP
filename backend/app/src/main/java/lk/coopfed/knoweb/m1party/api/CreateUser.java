package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;

/**
 * Creates a user of an entity and its login at the identity provider (21A section 6, doc 21
 * section 5.1). The user starts PENDING, with no credential: the first one is issued by
 * {@link ResetCredential} (a temporary password, or a PIN for a till user).
 *
 * @param homeEntityId   the entity the user belongs to; null means the caller's entity
 * @param userKind       BACK_OFFICE, TILL, BOTH or EXTERNAL (EXTERNAL by the Federation only)
 * @param language       en, si or ta
 * @param succeedsUserId a deactivated user this one succeeds, for returning staff (doc 21 DR-5)
 */
public record CreateUser(
        UUID homeEntityId,
        String username,
        String displayName,
        String language,
        String userKind,
        UUID succeedsUserId) {}
