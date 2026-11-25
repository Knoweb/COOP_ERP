package lk.coopfed.knoweb.m7customers.internal.loyalty;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
import lk.coopfed.knoweb.m7customers.api.ConfigureLoyaltyPolicy;
import lk.coopfed.knoweb.m7customers.api.LoyaltyPolicyConfigured;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles configuring or updating loyalty reward and insurance policy parameters (PGL-M3-03).
 * Supports configurable thresholds (e.g. Rs. 20,000 qualifying spend, 3+ months tenure, 200 points,
 * and Rs. 50,000 benefit).
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
public class ConfigureLoyaltyPolicyHandler implements Handles<ConfigureLoyaltyPolicy, UUID> {

    public static final String AUDIT_POLICY_CONFIGURED = "LOYALTY_POLICY_CONFIGURED";

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    public ConfigureLoyaltyPolicyHandler(
            JdbcTemplate jdbc, ConfigRegistry config, Clock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ConfigureLoyaltyPolicy command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null || scope.entityId() == null) {
            throw new ProblemException("m7.scope.own_required");
        }

        String policyName = command.policyName() != null ? command.policyName().trim() : "DEFAULT";
        if (policyName.isBlank()) {
            throw new ProblemException("m7.loyalty.policy_name_required");
        }

        BigDecimal spendThreshold = command.monthlySpendThreshold();
        if (spendThreshold == null) {
            spendThreshold = config.get("customers.loyalty.monthly_spend_threshold", scope)
                    .map(BigDecimal::new)
                    .orElse(new BigDecimal("20000.00"));
        }
        if (spendThreshold.compareTo(BigDecimal.ZERO) < 0) {
            throw new ProblemException("m7.loyalty.spend_amount_invalid");
        }

        Integer minMonths = command.minMembershipMonths();
        if (minMonths == null) {
            minMonths = config.getInt("customers.loyalty.min_membership_months", scope, 3);
        }
        if (minMonths < 0) {
            throw new ProblemException("m7.loyalty.period_invalid");
        }

        Integer rewardPoints = command.monthlyRewardPoints();
        if (rewardPoints == null) {
            rewardPoints = config.getInt("customers.loyalty.monthly_reward_points", scope, 200);
        }
        if (rewardPoints < 0) {
            throw new ProblemException("request.invalid");
        }

        BigDecimal insuranceBenefit = command.insuranceBenefitAmount();
        if (insuranceBenefit == null) {
            insuranceBenefit = config.get("customers.loyalty.insurance_benefit_amount", scope)
                    .map(BigDecimal::new)
                    .orElse(new BigDecimal("50000.00"));
        }
        if (insuranceBenefit.compareTo(BigDecimal.ZERO) < 0) {
            throw new ProblemException("request.invalid");
        }

        boolean active = command.active() != null ? command.active() : true;
        Instant now = clock.instant();
        UUID ownerEntityId = scope.entityId();

        // Check if existing policy
        UUID existingPolicyId = jdbc.query(
                "SELECT policy_id FROM customers.loyalty_reward_policy WHERE owner_entity_id = ? AND policy_name = ?",
                rs -> rs.next() ? (UUID) rs.getObject("policy_id") : null,
                ownerEntityId,
                policyName);

        UUID policyId;
        Map<String, Object> before = null;
        if (existingPolicyId != null) {
            policyId = existingPolicyId;
            before = jdbc.queryForMap(
                    "SELECT policy_id, policy_name, monthly_spend_threshold, min_membership_months, monthly_reward_points, insurance_benefit_amount, active FROM customers.loyalty_reward_policy WHERE policy_id = ?",
                    policyId);

            jdbc.update(
                    """
                    UPDATE customers.loyalty_reward_policy
                       SET monthly_spend_threshold = ?,
                           min_membership_months = ?,
                           monthly_reward_points = ?,
                           insurance_benefit_amount = ?,
                           active = ?,
                           updated_at = ?
                     WHERE policy_id = ?
                    """,
                    spendThreshold,
                    minMonths,
                    rewardPoints,
                    insuranceBenefit,
                    active,
                    Timestamp.from(now),
                    policyId);
        } else {
            policyId = Ids.next();
            jdbc.update(
                    """
                    INSERT INTO customers.loyalty_reward_policy
                        (policy_id, owner_entity_id, policy_name, monthly_spend_threshold,
                         min_membership_months, monthly_reward_points, insurance_benefit_amount,
                         active, created_at, updated_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    policyId,
                    ownerEntityId,
                    policyName,
                    spendThreshold,
                    minMonths,
                    rewardPoints,
                    insuranceBenefit,
                    active,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    scope.userId());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("policy_id", policyId.toString());
        after.put("policy_name", policyName);
        after.put("monthly_spend_threshold", spendThreshold.toPlainString());
        after.put("min_membership_months", minMonths);
        after.put("monthly_reward_points", rewardPoints);
        after.put("insurance_benefit_amount", insuranceBenefit.toPlainString());
        after.put("active", active);

        audit.record(AUDIT_POLICY_CONFIGURED, Subject.of("loyalty_reward_policy", policyId), before, after, scope);

        events.publish(new LoyaltyPolicyConfigured(
                policyId,
                ownerEntityId,
                policyName,
                spendThreshold,
                minMonths,
                rewardPoints,
                insuranceBenefit,
                active,
                now));

        return policyId;
    }
}
