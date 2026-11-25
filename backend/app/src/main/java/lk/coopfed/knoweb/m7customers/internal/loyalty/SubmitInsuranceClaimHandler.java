package lk.coopfed.knoweb.m7customers.internal.loyalty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m7customers.api.LoyaltyInsuranceClaimSubmitted;
import lk.coopfed.knoweb.m7customers.api.SubmitInsuranceClaim;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles submission and initial eligibility verification of loyalty insurance benefit claims (PGL-M3-03).
 * Supports the death-benefit workflow by verifying member tenure and monthly qualifying spend history.
 * Strictly avoids automated cash payouts.
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
public class SubmitInsuranceClaimHandler implements Handles<SubmitInsuranceClaim, UUID> {

    public static final String AUDIT_CLAIM_SUBMITTED = "INSURANCE_CLAIM_SUBMITTED";
    public static final Set<String> ALLOWED_CLAIM_TYPES =
            Set.of("DEATH_BENEFIT", "HOSPITALIZATION", "CRITICAL_ILLNESS", "ACCIDENT", "OTHER");

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final Clock clock;
    private final ObjectMapper json;
    private final AuditFacade audit;
    private final EventPublisher events;

    public SubmitInsuranceClaimHandler(
            JdbcTemplate jdbc,
            ConfigRegistry config,
            Clock clock,
            ObjectMapper json,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SubmitInsuranceClaim command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null || scope.entityId() == null) {
            throw new ProblemException("m7.scope.own_required");
        }
        if (command.loyaltyId() == null) {
            throw new ProblemException("m7.loyalty.not_found");
        }
        if (command.claimType() == null
                || !ALLOWED_CLAIM_TYPES.contains(command.claimType().toUpperCase())) {
            throw new ProblemException("m7.loyalty.claim_type_invalid");
        }
        if (command.claimantRelationship() == null
                || command.claimantRelationship().isBlank()) {
            throw new ProblemException("m7.loyalty.claim_relationship_required");
        }
        if (command.supportingDocumentRef() == null
                || command.supportingDocumentRef().isBlank()) {
            throw new ProblemException("m7.loyalty.claim_doc_ref_required");
        }
        if (command.incidentDate() == null || command.incidentDate().isAfter(LocalDate.now(clock))) {
            throw new ProblemException("m7.loyalty.claim_incident_date_invalid");
        }

        UUID ownerEntityId = scope.entityId();

        // Check loyalty customer
        Map<String, Object> customer = jdbc.query(
                "SELECT customer_id, enrolled_at, status FROM customers.loyalty_customer WHERE loyalty_id = ? AND owner_entity_id = ?",
                rs -> rs.next()
                        ? Map.of(
                                "customer_id", rs.getObject("customer_id"),
                                "enrolled_at", rs.getTimestamp("enrolled_at"),
                                "status", rs.getString("status"))
                        : null,
                command.loyaltyId(),
                ownerEntityId);

        if (customer == null) {
            throw new ProblemException("m7.loyalty.not_found");
        }

        UUID customerId = (UUID) customer.get("customer_id");
        Timestamp enrolledAtTs = (Timestamp) customer.get("enrolled_at");
        LocalDate enrolledDate =
                enrolledAtTs != null ? enrolledAtTs.toLocalDateTime().toLocalDate() : command.incidentDate();

        // Resolve Policy
        Map<String, Object> policyRow = jdbc.query(
                """
                SELECT monthly_spend_threshold, min_membership_months, insurance_benefit_amount
                  FROM customers.loyalty_reward_policy
                 WHERE owner_entity_id = ? AND active = true
                 ORDER BY updated_at DESC
                 LIMIT 1
                """,
                rs -> rs.next()
                        ? Map.of(
                                "threshold", rs.getBigDecimal("monthly_spend_threshold"),
                                "min_months", rs.getInt("min_membership_months"),
                                "insurance_amount", rs.getBigDecimal("insurance_benefit_amount"))
                        : null,
                ownerEntityId);

        BigDecimal spendThreshold;
        int minMembershipMonths;
        BigDecimal insuranceBenefitAmount;

        if (policyRow != null) {
            spendThreshold = (BigDecimal) policyRow.get("threshold");
            minMembershipMonths = (int) policyRow.get("min_months");
            insuranceBenefitAmount = (BigDecimal) policyRow.get("insurance_amount");
        } else {
            spendThreshold = config.get("customers.loyalty.monthly_spend_threshold", scope)
                    .map(BigDecimal::new)
                    .orElse(new BigDecimal("20000.00"));
            minMembershipMonths = config.getInt("customers.loyalty.min_membership_months", scope, 3);
            insuranceBenefitAmount = config.get("customers.loyalty.insurance_benefit_amount", scope)
                    .map(BigDecimal::new)
                    .orElse(new BigDecimal("50000.00"));
        }

        // Evaluate eligibility at incident date
        int membershipMonths = (int) ChronoUnit.MONTHS.between(enrolledDate, command.incidentDate());
        if (membershipMonths < 0) {
            membershipMonths = 0;
        }

        YearMonth incidentMonth = YearMonth.from(command.incidentDate());
        LocalDate monthStart = incidentMonth.atDay(1);
        LocalDate monthEnd = incidentMonth.atEndOfMonth();

        BigDecimal spend = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount), 0)
                  FROM customers.loyalty_spend
                 WHERE owner_entity_id = ?
                   AND loyalty_id = ?
                   AND spend_date >= ?
                   AND spend_date <= ?
                """,
                BigDecimal.class,
                ownerEntityId,
                command.loyaltyId(),
                Date.valueOf(monthStart),
                Date.valueOf(monthEnd));
        if (spend == null) {
            spend = BigDecimal.ZERO;
        }

        // Also check if previously recorded monthly reward shows insurance_eligible
        Boolean previouslyEligible = jdbc.query(
                "SELECT insurance_eligible FROM customers.loyalty_monthly_reward WHERE owner_entity_id = ? AND loyalty_id = ? AND period_month = ?",
                rs -> rs.next() ? rs.getBoolean("insurance_eligible") : null,
                ownerEntityId,
                command.loyaltyId(),
                incidentMonth.toString());

        boolean tenureOk = membershipMonths >= minMembershipMonths;
        boolean spendOk = spend.compareTo(spendThreshold) >= 0;
        boolean eligibilityVerified = (previouslyEligible != null && previouslyEligible) || (tenureOk && spendOk);

        String claimStatus = eligibilityVerified ? "ELIGIBLE_SUPPORTED" : "SUBMITTED";

        Map<String, Object> snapshotMap = new LinkedHashMap<>();
        snapshotMap.put("incident_date", command.incidentDate().toString());
        snapshotMap.put("enrolled_date", enrolledDate.toString());
        snapshotMap.put("membership_months", membershipMonths);
        snapshotMap.put("min_membership_months", minMembershipMonths);
        snapshotMap.put("qualifying_spend", spend.toPlainString());
        snapshotMap.put("spend_threshold", spendThreshold.toPlainString());
        snapshotMap.put("policy_benefit_amount", insuranceBenefitAmount.toPlainString());
        snapshotMap.put("tenure_ok", tenureOk);
        snapshotMap.put("spend_ok", spendOk);
        snapshotMap.put("eligibility_verified", eligibilityVerified);

        String snapshotJson;
        try {
            snapshotJson = json.writeValueAsString(snapshotMap);
        } catch (JsonProcessingException e) {
            snapshotJson = "{}";
        }

        UUID claimId = Ids.next();
        Instant now = clock.instant();

        jdbc.update(
                """
                INSERT INTO customers.loyalty_insurance_claim
                    (claim_id, owner_entity_id, loyalty_id, customer_id, claim_type,
                     claim_status, policy_benefit_amount, incident_date, claimant_relationship,
                     supporting_document_ref, eligibility_verified, eligibility_snapshot,
                     submitted_at, submitted_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """,
                claimId,
                ownerEntityId,
                command.loyaltyId(),
                customerId,
                command.claimType().toUpperCase(),
                claimStatus,
                insuranceBenefitAmount,
                Date.valueOf(command.incidentDate()),
                command.claimantRelationship().trim(),
                command.supportingDocumentRef().trim(),
                eligibilityVerified,
                snapshotJson,
                Timestamp.from(now),
                scope.userId(),
                Timestamp.from(now),
                scope.userId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("claim_id", claimId.toString());
        after.put("loyalty_id", command.loyaltyId().toString());
        after.put("claim_type", command.claimType().toUpperCase());
        after.put("claim_status", claimStatus);
        after.put("policy_benefit_amount", insuranceBenefitAmount.toPlainString());
        after.put("eligibility_verified", eligibilityVerified);

        audit.record(AUDIT_CLAIM_SUBMITTED, Subject.of("loyalty_insurance_claim", claimId), null, after, scope);

        events.publish(new LoyaltyInsuranceClaimSubmitted(
                claimId,
                ownerEntityId,
                command.loyaltyId(),
                customerId,
                command.claimType().toUpperCase(),
                insuranceBenefitAmount,
                command.incidentDate(),
                eligibilityVerified,
                claimStatus,
                now));

        return claimId;
    }
}
