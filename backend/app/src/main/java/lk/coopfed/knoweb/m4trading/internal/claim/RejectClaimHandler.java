package lk.coopfed.knoweb.m4trading.internal.claim;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.ClaimRejected;
import lk.coopfed.knoweb.m4trading.api.RejectClaim;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RejectClaim (24A section 6; doc 24 section 4.5): the seller refuses the claim.
 *
 * <p>Guards, in order: the seller's entity-wide OWN scope; a claim raised with the caller ({@code
 * m4.claim.not_found}); a reason; not decided ({@code m4.claim.decided}); every photograph COMPLETE
 * ({@code m4.claim.evidence_pending}: the seller has seen the evidence it refuses).
 *
 * <p>Mutation: the seller's own {@code claim_decision} row, REJECTED with the reason; the quantity
 * it held on the GRN lines is free to claim again. Audit CLAIM_REJECTED; event claim.rejected.v1.
 */
@Service
@CommandHandler(permission = "del.claim.decide")
public class RejectClaimHandler implements Handles<RejectClaim, Void> {

    static final String AUDIT_REJECTED = "CLAIM_REJECTED";

    private final JdbcTemplate jdbc;
    private final ClaimReads claims;
    private final Attachments attachments;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RejectClaimHandler(
            JdbcTemplate jdbc,
            ClaimReads claims,
            Attachments attachments,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.attachments = attachments;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RejectClaim command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        String reason = TradingGuards.required(command.reason(), "reason").strip();
        ClaimReads.Claim claim = ClaimGuards.claimWith(claims, jdbc, command.claimId(), seller);
        ClaimGuards.requireEvidenceComplete(claim, attachments);
        UUID buyer = claim.header().ownerEntityId();

        Instant decidedAt = clock.now();
        jdbc.update(
                """
                insert into trading.claim_decision (claim_document_id, decision, reason, decided_by, decided_at,
                    owner_entity_id, counterparty_entity_id)
                values (?, 'REJECTED', ?, ?, ?, ?, ?)
                """,
                claim.header().id(),
                reason,
                scope.userId(),
                Timestamp.from(decidedAt),
                seller,
                buyer);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", ClaimReads.REJECTED);
        after.put("reason", reason);
        audit.record(
                AUDIT_REJECTED,
                Subject.of("claim", claim.header().id()),
                Map.of("status", ClaimReads.RAISED),
                after,
                scope);
        events.publish(new ClaimRejected(
                claim.header().id(), claim.grnId(), seller, buyer, reason, scope.userId(), decidedAt));
        return null;
    }
}
