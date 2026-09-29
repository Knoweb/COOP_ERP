package lk.coopfed.knoweb.m4trading.internal.claim;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.ClaimLine;
import lk.coopfed.knoweb.m4trading.api.ClaimReturnDispatched;
import lk.coopfed.knoweb.m4trading.api.DispatchClaimReturn;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DispatchClaimReturn: the buyer sends back what the seller accepted of a claim approved with the
 * return required (doc 24 section 3.5: "if return_required, M5 posts a return movement").
 *
 * <p>Guards, in order: the buyer's entity-wide OWN scope; the caller's own claim ({@code
 * m4.claim.not_found}); approved with the return required ({@code m4.claim.return_not_required});
 * not sent back already (an advisory lock per claim; {@code m4.claim.returned_already}).
 *
 * <p>Mutation: the buyer's own {@code claim_return} row at the claim's (the GRN's) location. Audit
 * CLAIM_RETURN_DISPATCHED; event claim.return_dispatched.v1 with the accepted quantities, on which
 * M5 posts RETURN_TO_SELLER out of the buyer's lots in the buyer's scope.
 */
@Service
@CommandHandler(permission = "del.claim.raise")
public class DispatchClaimReturnHandler implements Handles<DispatchClaimReturn, Void> {

    static final String AUDIT_RETURNED = "CLAIM_RETURN_DISPATCHED";

    private final JdbcTemplate jdbc;
    private final ClaimReads claims;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    DispatchClaimReturnHandler(
            JdbcTemplate jdbc, ClaimReads claims, TradingClock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(DispatchClaimReturn command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID buyer = scope.entityId();
        ClaimReads.Claim claim = claims.claim(command.claimId())
                .filter(found -> buyer.equals(found.header().ownerEntityId()))
                .orElseThrow(() -> new ProblemException("m4.claim.not_found"));
        ClaimReads.Decision decision = claims.decision(command.claimId())
                .filter(found -> ClaimReads.APPROVED.equals(found.decision()) && found.returnRequired())
                .orElseThrow(() -> new ProblemException("m4.claim.return_not_required"));
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "claim-return-" + command.claimId());
        if (claims.returnedAt(command.claimId()).isPresent()) {
            throw new ProblemException("m4.claim.returned_already");
        }

        List<ClaimLine> returned = new ArrayList<>();
        for (ClaimLine line : claim.lines()) {
            BigDecimal qty = decision.approvedByLine().getOrDefault(line.claimLineId(), BigDecimal.ZERO);
            if (qty.signum() > 0) {
                returned.add(new ClaimLine(
                        line.claimLineId(), line.grnLineId(), line.skuId(), line.batchId(), line.uomCode(), qty));
            }
        }
        UUID location = claim.header().locationId();
        UUID seller = claim.header().counterpartyEntityId();
        Instant dispatchedAt = clock.now();
        jdbc.update(
                """
                insert into trading.claim_return (claim_document_id, location_id, dispatched_by, dispatched_at,
                    owner_entity_id, counterparty_entity_id)
                values (?, ?, ?, ?, ?, ?)
                """,
                command.claimId(),
                location,
                scope.userId(),
                Timestamp.from(dispatchedAt),
                buyer,
                seller);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "RETURN_DISPATCHED");
        after.put("locationId", location);
        after.put("lines", returned.size());
        audit.record(AUDIT_RETURNED, Subject.of("claim", command.claimId()), null, after, scope);
        events.publish(new ClaimReturnDispatched(
                command.claimId(), buyer, seller, location, dispatchedAt, List.copyOf(returned)));
        return null;
    }
}
