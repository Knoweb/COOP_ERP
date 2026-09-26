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
import lk.coopfed.knoweb.m1party.api.ExternalGrantRevoked;
import lk.coopfed.knoweb.m1party.api.RevokeExternalView;
import lk.coopfed.knoweb.m1party.internal.entity.FederationCallers;
import lk.coopfed.knoweb.m1party.query.ExternalGrantView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RevokeExternalView (doc 21 section 4.6: ACTIVE to REVOKED, reason, gov.external.grant with
 * MFA). Guards: the Federation ({@code m1.grant.federation_required}); the grant exists
 * ({@code m1.grant.not_found}); it is ACTIVE and its window has not already passed
 * ({@code m1.grant.not_active}: an ended grant expires, it is not revoked); a reason
 * ({@code m1.grant.reason_required}). The row keeps its window; the status is what ends it, at
 * once: the kernel reads the status on every resolution ({@code JdbcUserScopes}) and the event
 * empties the entry it cached.
 */
@Service
@CommandHandler(permission = "gov.external.grant", requiresMfa = true)
class RevokeExternalViewHandler implements Handles<RevokeExternalView, UUID> {

    static final String AUDIT_REVOKED = "EXTERNAL_GRANT_REVOKED";

    private final ExternalGrantRows rows;
    private final FederationCallers federation;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RevokeExternalViewHandler(
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
    public UUID handle(RevokeExternalView command, ScopeContext scope) {

        federation.require(scope, "m1.grant.federation_required");

        UUID grantId = command.grantId();
        if (grantId == null) {
            throw new ProblemException("m1.grant.not_found");
        }
        ExternalGrantView grant = rows.find(grantId)
                .orElseThrow(() -> new ProblemException("m1.grant.not_found", Map.of("grantId", grantId)));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        if (!ExternalGrantRows.ACTIVE.equals(grant.status())
                || !grant.validUntil().isAfter(now)) {
            throw new ProblemException("m1.grant.not_active", Map.of("grantId", grantId, "status", grant.status()));
        }

        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("m1.grant.reason_required");
        }
        String reason = command.reason().strip();

        int updated = jdbc.update(
                "update security.external_grant set status = 'REVOKED' where grant_id = ? and status = 'ACTIVE'",
                grantId);
        if (updated != 1) {
            // Expired or revoked by another transaction since it was read.
            throw new ProblemException("m1.grant.not_active", Map.of("grantId", grantId));
        }

        audit.record(
                AUDIT_REVOKED,
                Subject.of("external_grant", grantId),
                Map.of("status", ExternalGrantRows.ACTIVE),
                Map.of("status", ExternalGrantRows.REVOKED, "revokedAt", now),
                scope,
                reason);

        events.publish(new ExternalGrantRevoked(
                grantId, grant.granteeUserId(), grant.scopeEntityIds(), grant.validUntil(), now));

        return grantId;
    }
}
