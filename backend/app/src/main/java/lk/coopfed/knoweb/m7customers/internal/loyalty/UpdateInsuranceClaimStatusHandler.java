package lk.coopfed.knoweb.m7customers.internal.loyalty;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.LoyaltyInsuranceClaimStatusUpdated;
import lk.coopfed.knoweb.m7customers.api.UpdateInsuranceClaimStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles lifecycle status transitions for loyalty insurance benefit claims (PGL-M3-03).
 * Transitions: SUBMITTED -> ELIGIBLE_SUPPORTED -> APPROVED / REJECTED -> SETTLED_EXTERNALLY.
 * Strictly maintains no automatic cash ledger movements.
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
public class UpdateInsuranceClaimStatusHandler implements Handles<UpdateInsuranceClaimStatus, Void> {

    public static final String AUDIT_STATUS_UPDATED = "INSURANCE_CLAIM_STATUS_UPDATED";
    public static final Set<String> ALLOWED_STATUSES =
            Set.of("SUBMITTED", "ELIGIBLE_SUPPORTED", "APPROVED", "REJECTED", "SETTLED_EXTERNALLY");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    public UpdateInsuranceClaimStatusHandler(JdbcTemplate jdbc, Clock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(UpdateInsuranceClaimStatus command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null || scope.entityId() == null) {
            throw new ProblemException("m7.scope.own_required");
        }
        if (command.claimId() == null) {
            throw new ProblemException("m7.loyalty.claim_not_found");
        }
        if (command.status() == null
                || !ALLOWED_STATUSES.contains(command.status().toUpperCase())) {
            throw new ProblemException("m7.loyalty.claim_status_invalid");
        }

        String newStatus = command.status().toUpperCase();
        if ("REJECTED".equals(newStatus)
                && (command.rejectionReason() == null
                        || command.rejectionReason().isBlank())) {
            throw new ProblemException("m7.loyalty.claim_rejection_reason_required");
        }

        UUID ownerEntityId = scope.entityId();

        Map<String, Object> claim = jdbc.query(
                "SELECT loyalty_id, claim_status, rejection_reason FROM customers.loyalty_insurance_claim WHERE claim_id = ? AND owner_entity_id = ?",
                rs -> rs.next()
                        ? Map.of(
                                "loyalty_id",
                                rs.getObject("loyalty_id"),
                                "claim_status",
                                rs.getString("claim_status"),
                                "rejection_reason",
                                rs.getString("rejection_reason") != null ? rs.getString("rejection_reason") : "")
                        : null,
                command.claimId(),
                ownerEntityId);

        if (claim == null) {
            throw new ProblemException("m7.loyalty.claim_not_found");
        }

        UUID loyaltyId = (UUID) claim.get("loyalty_id");
        String previousStatus = (String) claim.get("claim_status");
        Instant now = clock.instant();

        jdbc.update(
                """
                UPDATE customers.loyalty_insurance_claim
                   SET claim_status = ?,
                       rejection_reason = ?,
                       updated_at = ?,
                       updated_by = ?
                 WHERE claim_id = ?
                """,
                newStatus,
                command.rejectionReason(),
                Timestamp.from(now),
                scope.userId(),
                command.claimId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("claim_id", command.claimId().toString());
        before.put("claim_status", previousStatus);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("claim_id", command.claimId().toString());
        after.put("claim_status", newStatus);
        if (command.rejectionReason() != null) {
            after.put("rejection_reason", command.rejectionReason());
        }

        audit.record(
                AUDIT_STATUS_UPDATED, Subject.of("loyalty_insurance_claim", command.claimId()), before, after, scope);

        events.publish(new LoyaltyInsuranceClaimStatusUpdated(
                command.claimId(), ownerEntityId, loyaltyId, previousStatus, newStatus, now));

        return null;
    }
}
