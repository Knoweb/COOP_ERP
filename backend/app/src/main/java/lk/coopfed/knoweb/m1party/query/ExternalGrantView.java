package lk.coopfed.knoweb.m1party.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One external grant as the Federation's register of grants shows it (21A section 7). */
public record ExternalGrantView(
        UUID grantId,
        UUID granteeUserId,
        List<UUID> scopeEntityIds,
        Instant validFrom,
        Instant validUntil,
        String reason,
        String status) {}
