package lk.coopfed.knoweb.m1party.query;

import java.time.Instant;
import java.util.UUID;

/**
 * A user as an administrator sees one. Whether a PIN is set and when it last changed, never the
 * PIN's hash: the hash travels only in the operator snapshot (21A section 7.3).
 */
public record UserView(
        UUID userId,
        UUID homeEntityId,
        String username,
        String displayName,
        String language,
        String userKind,
        String status,
        boolean pinSet,
        Instant pinChangedAt,
        UUID succeedsUserId) {}
