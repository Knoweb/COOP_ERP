package lk.coopfed.knoweb.m1party.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** doc 21 section 5.3: grant id, grantee, scope, valid_until; and when it was revoked. */
public record ExternalGrantRevoked(
        UUID grantId, UUID granteeUserId, List<UUID> scopeEntityIds, Instant validUntil, Instant revokedAt)
        implements DomainEvent {

    public static final String TYPE = "external_grant.revoked.v1";
}
