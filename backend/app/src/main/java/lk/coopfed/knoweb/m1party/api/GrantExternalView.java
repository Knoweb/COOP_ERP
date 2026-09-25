package lk.coopfed.knoweb.m1party.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Gives an external user (a regulator or an auditor) read-only access to the rows of the listed
 * entities until {@code validUntil} (21A section 6; doc 21 section 4.6). {@code validFrom} may
 * be null: the grant starts now.
 */
public record GrantExternalView(
        UUID granteeUserId, List<UUID> scopeEntityIds, Instant validFrom, Instant validUntil, String reason) {}
