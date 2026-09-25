package lk.coopfed.knoweb.m1party.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** doc 21 section 5.3: grant id, grantee, scope, valid_until. */
public record ExternalGrantIssued(
        UUID grantId, UUID granteeUserId, List<UUID> scopeEntityIds, Instant validFrom, Instant validUntil)
        implements DomainEvent {

    public static final String TYPE = "external_grant.issued.v1";
}
