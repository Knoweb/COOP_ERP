package lk.coopfed.knoweb.m1party.internal.grant;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.ExpireExternalGrant;
import lk.coopfed.knoweb.m1party.api.ExternalGrantExpired;
import lk.coopfed.knoweb.m1party.internal.entity.FederationCallers;
import lk.coopfed.knoweb.m1party.query.ExternalGrantView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACTIVE to EXPIRED by the clock (doc 21 section 4.6), sent by {@link ExternalGrantExpiryJob} in
 * the Federation's system scope. Guards: the Federation ({@code m1.grant.federation_required});
 * the grant exists ({@code m1.grant.not_found}); it is ACTIVE ({@code m1.grant.not_active}) and
 * its window has passed ({@code m1.grant.not_due}).
 *
 * <p>The permission is the grant permission: the system acts for the Federation, which owns the
 * grant. No request carries this command, so the kernel checks no permission on it (K-03b
 * checks HTTP commands); the annotation is what the build requires of every writer.
 */
@Service
@CommandHandler(permission = "gov.external.grant")
class ExpireExternalGrantHandler implements Handles<ExpireExternalGrant, UUID> {

    static final String AUDIT_EXPIRED = "EXTERNAL_GRANT_EXPIRED";

    private final ExternalGrantRows rows;
    private final FederationCallers federation;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ExpireExternalGrantHandler(
            ExternalGrantRows rows,
            FederationCallers federation,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.rows = rows;
        this.federation = federation;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ExpireExternalGrant command, ScopeContext scope) {

        federation.require(scope, "m1.grant.federation_required");

        UUID grantId = command.grantId();
        if (grantId == null) {
            throw new ProblemException("m1.grant.not_found");
        }
        ExternalGrantView grant = rows.find(grantId)
                .orElseThrow(() -> new ProblemException("m1.grant.not_found", Map.of("grantId", grantId)));

        if (!ExternalGrantRows.ACTIVE.equals(grant.status())) {
            throw new ProblemException("m1.grant.not_active", Map.of("grantId", grantId, "status", grant.status()));
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (grant.validUntil().isAfter(now)) {
            throw new ProblemException(
                    "m1.grant.not_due", Map.of("grantId", grantId, "validUntil", grant.validUntil()));
        }

        int updated = jdbc.update(
                "update security.external_grant set status = 'EXPIRED' where grant_id = ? and status = 'ACTIVE'",
                grantId);
        if (updated != 1) {
            throw new ProblemException("m1.grant.not_active", Map.of("grantId", grantId));
        }

        audit.record(
                AUDIT_EXPIRED,
                Subject.of("external_grant", grantId),
                Map.of("status", ExternalGrantRows.ACTIVE),
                Map.of("status", ExternalGrantRows.EXPIRED, "validUntil", grant.validUntil()),
                scope);

        events.publish(
                new ExternalGrantExpired(grantId, grant.granteeUserId(), grant.scopeEntityIds(), grant.validUntil()));

        return grantId;
    }
}
