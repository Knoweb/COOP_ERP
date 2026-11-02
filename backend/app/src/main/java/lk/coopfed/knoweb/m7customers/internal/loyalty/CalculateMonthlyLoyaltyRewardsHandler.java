package lk.coopfed.knoweb.m7customers.internal.loyalty;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import lk.coopfed.knoweb.m7customers.api.CalculateMonthlyLoyaltyRewards;
import lk.coopfed.knoweb.m7customers.api.LoyaltyMonthlyRewardCalculated;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles calculating monthly qualifying spend, loyalty points reward, and insurance eligibility (PGL-M3-03).
 * Enforces configurable policy rules: members older than configured membership age spending above
 * configured monthly threshold receive configured reward points and qualify for insurance benefits.
 */
@Service
@CommandHandler(permission = "cus.customer.manage")
public class CalculateMonthlyLoyaltyRewardsHandler implements Handles<CalculateMonthlyLoyaltyRewards, List<UUID>> {

    public static final String AUDIT_MONTHLY_REWARD_CALCULATED = "LOYALTY_MONTHLY_REWARD_CALCULATED";

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    public CalculateMonthlyLoyaltyRewardsHandler(
            JdbcTemplate jdbc, ConfigRegistry config, Clock clock, AuditFacade audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public List<UUID> handle(CalculateMonthlyLoyaltyRewards command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        if (scope == null || scope.entityId() == null) {
            throw new ProblemException("m7.scope.own_required");
        }
        if (command.periodMonth() == null || !command.periodMonth().matches("^\\d{4}-\\d{2}$")) {
            throw new ProblemException("m7.loyalty.period_invalid");
        }

        UUID ownerEntityId = scope.entityId();
        YearMonth period = YearMonth.parse(command.periodMonth());
        LocalDate monthStart = period.atDay(1);
        LocalDate monthEnd = period.atEndOfMonth();

        // 1. Resolve Policy (Check entity policy table first, fallback to ConfigRegistry)
        Map<String, Object> policyRow = jdbc.query(
                """
                SELECT monthly_spend_threshold, min_membership_months, monthly_reward_points, insurance_benefit_amount
                  FROM customers.loyalty_reward_policy
                 WHERE owner_entity_id = ? AND active = true
                 ORDER BY updated_at DESC
                 LIMIT 1
                """,
                rs -> rs.next()
                        ? Map.of(
                                "threshold", rs.getBigDecimal("monthly_spend_threshold"),
                                "min_months", rs.getInt("min_membership_months"),
                                "reward_points", rs.getInt("monthly_reward_points"),
                                "insurance_amount", rs.getBigDecimal("insurance_benefit_amount"))
                        : null,
                ownerEntityId);

        BigDecimal spendThreshold;
        int minMembershipMonths;
        int monthlyRewardPoints;

        if (policyRow != null) {
            spendThreshold = (BigDecimal) policyRow.get("threshold");
            minMembershipMonths = (int) policyRow.get("min_months");
            monthlyRewardPoints = (int) policyRow.get("reward_points");
        } else {
            spendThreshold = config.get("customers.loyalty.monthly_spend_threshold", scope)
                    .map(BigDecimal::new)
                    .orElse(new BigDecimal("20000.00"));
            minMembershipMonths = config.getInt("customers.loyalty.min_membership_months", scope, 3);
            monthlyRewardPoints = config.getInt("customers.loyalty.monthly_reward_points", scope, 200);
        }

        // 2. Fetch target loyalty customers
        String custSql =
                """
                SELECT loyalty_id, customer_id, enrolled_at
                  FROM customers.loyalty_customer
                 WHERE owner_entity_id = ? AND status = 'ACTIVE'
                """
                        + (command.customerId() != null ? " AND customer_id = ?" : "");

        List<Map<String, Object>> customers = command.customerId() != null
                ? jdbc.queryForList(custSql, ownerEntityId, command.customerId())
                : jdbc.queryForList(custSql, ownerEntityId);

        List<UUID> rewardIds = new ArrayList<>();
        Instant now = clock.instant();

        for (Map<String, Object> c : customers) {
            UUID loyaltyId = (UUID) c.get("loyalty_id");
            UUID customerId = (UUID) c.get("customer_id");
            Timestamp enrolledAtTs = (Timestamp) c.get("enrolled_at");
            LocalDate enrolledDate =
                    enrolledAtTs != null ? enrolledAtTs.toLocalDateTime().toLocalDate() : monthStart;

            // Calculate qualifying spend in periodMonth
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
                    loyaltyId,
                    Date.valueOf(monthStart),
                    Date.valueOf(monthEnd));
            if (spend == null) {
                spend = BigDecimal.ZERO;
            }

            // Membership tenure in months as of the end of the evaluation month
            int membershipMonths = (int) ChronoUnit.MONTHS.between(enrolledDate, monthEnd);
            if (membershipMonths < 0) {
                membershipMonths = 0;
            }

            boolean tenureEligible = membershipMonths >= minMembershipMonths;
            boolean spendEligible = spend.compareTo(spendThreshold) >= 0;
            boolean eligible = tenureEligible && spendEligible;

            int pointsAwarded = eligible ? monthlyRewardPoints : 0;
            boolean insuranceEligible = eligible;
            String rewardStatus = eligible ? "AWARDED" : "DISQUALIFIED";
            String reason = null;
            if (!eligible) {
                if (!tenureEligible && !spendEligible) {
                    reason = "TENURE_AND_SPEND_INSUFFICIENT";
                } else if (!tenureEligible) {
                    reason = "TENURE_TOO_SHORT";
                } else {
                    reason = "INSUFFICIENT_SPEND";
                }
            }

            UUID rewardId = Ids.next();

            // Check if existing evaluation for this month
            UUID existingRewardId = jdbc.query(
                    "SELECT reward_id FROM customers.loyalty_monthly_reward WHERE owner_entity_id = ? AND loyalty_id = ? AND period_month = ?",
                    rs -> rs.next() ? (UUID) rs.getObject("reward_id") : null,
                    ownerEntityId,
                    loyaltyId,
                    command.periodMonth());

            if (existingRewardId != null) {
                rewardId = existingRewardId;
                jdbc.update(
                        """
                        UPDATE customers.loyalty_monthly_reward
                           SET qualifying_spend = ?,
                               spend_threshold = ?,
                               membership_months = ?,
                               min_membership_months = ?,
                               points_awarded = ?,
                               insurance_eligible = ?,
                               reward_status = ?,
                               disqualification_reason = ?,
                               evaluated_at = ?,
                               evaluated_by = ?
                         WHERE reward_id = ?
                        """,
                        spend,
                        spendThreshold,
                        membershipMonths,
                        minMembershipMonths,
                        pointsAwarded,
                        insuranceEligible,
                        rewardStatus,
                        reason,
                        Timestamp.from(now),
                        scope.userId(),
                        rewardId);
            } else {
                jdbc.update(
                        """
                        INSERT INTO customers.loyalty_monthly_reward
                            (reward_id, owner_entity_id, loyalty_id, customer_id, period_month,
                             qualifying_spend, spend_threshold, membership_months, min_membership_months,
                             points_awarded, insurance_eligible, reward_status, disqualification_reason,
                             evaluated_at, evaluated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        rewardId,
                        ownerEntityId,
                        loyaltyId,
                        customerId,
                        command.periodMonth(),
                        spend,
                        spendThreshold,
                        membershipMonths,
                        minMembershipMonths,
                        pointsAwarded,
                        insuranceEligible,
                        rewardStatus,
                        reason,
                        Timestamp.from(now),
                        scope.userId());
            }

            // If points awarded, insert into points ledger (append-only)
            if (pointsAwarded > 0) {
                jdbc.update(
                        """
                        INSERT INTO customers.loyalty_points_ledger
                            (entry_id, owner_entity_id, loyalty_id, customer_id, points,
                             reason, period_month, reference_id, recorded_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        Ids.next(),
                        ownerEntityId,
                        loyaltyId,
                        customerId,
                        pointsAwarded,
                        "MONTHLY_QUALIFYING_REWARD",
                        command.periodMonth(),
                        rewardId,
                        Timestamp.from(now));
            }

            rewardIds.add(rewardId);

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("reward_id", rewardId.toString());
            after.put("loyalty_id", loyaltyId.toString());
            after.put("period_month", command.periodMonth());
            after.put("qualifying_spend", spend.toPlainString());
            after.put("points_awarded", pointsAwarded);
            after.put("insurance_eligible", insuranceEligible);
            after.put("reward_status", rewardStatus);

            audit.record(
                    AUDIT_MONTHLY_REWARD_CALCULATED,
                    Subject.of("loyalty_monthly_reward", rewardId),
                    null,
                    after,
                    scope);

            events.publish(new LoyaltyMonthlyRewardCalculated(
                    rewardId,
                    ownerEntityId,
                    loyaltyId,
                    customerId,
                    command.periodMonth(),
                    spend,
                    pointsAwarded,
                    insuranceEligible,
                    rewardStatus,
                    now));
        }

        return rewardIds;
    }
}
