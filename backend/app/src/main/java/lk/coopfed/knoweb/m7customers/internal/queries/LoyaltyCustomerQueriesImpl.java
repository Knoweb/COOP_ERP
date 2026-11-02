package lk.coopfed.knoweb.m7customers.internal.queries;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.internal.customer.NicHasher;
import lk.coopfed.knoweb.m7customers.internal.customer.NicNumbers;
import lk.coopfed.knoweb.m7customers.internal.customer.PhoneNumbers;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerQueries;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyEligibility;
import lk.coopfed.knoweb.m7customers.query.LoyaltyInsuranceClaimView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyInsuranceEligibilityView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyMonthlyRewardView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyRewardPolicyView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of LoyaltyCustomerQueries protected by Row Level Security (PGL-M3-01, PGL-M3-03).
 * Resolves centralized loyalty identities, evaluates branch eligibility, calculates rewards,
 * and queries insurance claims.
 */
@Service
@Transactional(readOnly = true)
class LoyaltyCustomerQueriesImpl implements LoyaltyCustomerQueries {

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final NicHasher nicHasher;
    private final ConfigRegistry config;
    private final ObjectMapper json;
    private final Clock clock;

    LoyaltyCustomerQueriesImpl(
            JdbcTemplate jdbc, NicHasher nicHasher, ConfigRegistry config, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.nicHasher = nicHasher;
        this.config = config;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public Optional<LoyaltyCustomerView> lookupByPhone(String phone, ScopeContext scope) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        Optional<String> e164 = PhoneNumbers.normalise(phone);
        if (e164.isEmpty()) {
            return Optional.empty();
        }
        List<LoyaltyCustomerView> list = jdbc.query(
                baseSelect() + " WHERE ph.phone = ? ORDER BY lc.created_at DESC LIMIT 1",
                (rs, rowNum) -> mapRow(rs),
                e164.get());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<LoyaltyCustomerView> lookupByMemberNo(String memberNo, ScopeContext scope) {
        if (memberNo == null || memberNo.isBlank()) {
            return Optional.empty();
        }
        List<LoyaltyCustomerView> list = jdbc.query(
                baseSelect()
                        + " WHERE lower(trim(lc.regional_member_no)) = lower(trim(?)) ORDER BY lc.created_at DESC LIMIT 1",
                (rs, rowNum) -> mapRow(rs),
                memberNo.trim());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<LoyaltyCustomerView> lookupByNic(String nic, ScopeContext scope) {
        if (nic == null || nic.isBlank()) {
            return Optional.empty();
        }
        Optional<String> canonical = NicNumbers.normalise(nic);
        if (canonical.isEmpty()) {
            return Optional.empty();
        }
        List<String> hashCandidates = nicHasher.candidates(canonical.get());
        if (hashCandidates.isEmpty()) {
            return Optional.empty();
        }

        String placeholders = String.join(",", Collections.nCopies(hashCandidates.size(), "?"));
        List<LoyaltyCustomerView> list = jdbc.query(
                baseSelect() + " WHERE c.nic_hash IN (" + placeholders + ") ORDER BY lc.created_at DESC LIMIT 1",
                (rs, rowNum) -> mapRow(rs),
                hashCandidates.toArray());
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<LoyaltyCustomerView> getLoyaltyCustomer(UUID customerId, ScopeContext scope) {
        if (customerId == null) {
            return Optional.empty();
        }
        List<LoyaltyCustomerView> list = jdbc.query(
                baseSelect() + " WHERE lc.customer_id = ? ORDER BY lc.created_at DESC LIMIT 1",
                (rs, rowNum) -> mapRow(rs),
                customerId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public LoyaltyEligibility checkEligibility(UUID customerId, UUID locationId, ScopeContext scope) {
        Optional<LoyaltyCustomerView> opt = getLoyaltyCustomer(customerId, scope);
        if (opt.isEmpty()) {
            return LoyaltyEligibility.notFound(locationId);
        }
        return evaluateEligibility(opt.get(), locationId);
    }

    @Override
    public LoyaltyEligibility checkEligibility(String identifier, UUID locationId, ScopeContext scope) {
        if (identifier == null || identifier.isBlank()) {
            return LoyaltyEligibility.notFound(locationId);
        }
        String trimmed = identifier.trim();

        // 1. Try UUID
        try {
            UUID cid = UUID.fromString(trimmed);
            Optional<LoyaltyCustomerView> opt = getLoyaltyCustomer(cid, scope);
            if (opt.isPresent()) {
                return evaluateEligibility(opt.get(), locationId);
            }
        } catch (IllegalArgumentException ignored) {
            // Not a UUID
        }

        // 2. Try Member Number
        Optional<LoyaltyCustomerView> byMember = lookupByMemberNo(trimmed, scope);
        if (byMember.isPresent()) {
            return evaluateEligibility(byMember.get(), locationId);
        }

        // 3. Try Phone
        Optional<LoyaltyCustomerView> byPhone = lookupByPhone(trimmed, scope);
        if (byPhone.isPresent()) {
            return evaluateEligibility(byPhone.get(), locationId);
        }

        // 4. Try NIC
        Optional<LoyaltyCustomerView> byNic = lookupByNic(trimmed, scope);
        if (byNic.isPresent()) {
            return evaluateEligibility(byNic.get(), locationId);
        }

        return LoyaltyEligibility.notFound(locationId);
    }

    @Override
    public List<LoyaltyCustomerView> listByRegion(String regionCode, ScopeContext scope) {
        if (regionCode == null || regionCode.isBlank()) {
            return Collections.emptyList();
        }
        return jdbc.query(
                baseSelect() + " WHERE lc.region_code = ? ORDER BY lc.created_at DESC",
                (rs, rowNum) -> mapRow(rs),
                regionCode.trim());
    }

    // --- PGL-M3-03 Queries ---

    @Override
    public Optional<LoyaltyRewardPolicyView> getActiveLoyaltyPolicy(ScopeContext scope) {
        List<LoyaltyRewardPolicyView> list = jdbc.query(
                """
                SELECT policy_id, owner_entity_id, policy_name, monthly_spend_threshold,
                       min_membership_months, monthly_reward_points, insurance_benefit_amount,
                       active, created_at, updated_at
                  FROM customers.loyalty_reward_policy
                 WHERE active = true
                 ORDER BY updated_at DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> new LoyaltyRewardPolicyView(
                        rs.getObject("policy_id", UUID.class),
                        rs.getObject("owner_entity_id", UUID.class),
                        rs.getString("policy_name"),
                        rs.getBigDecimal("monthly_spend_threshold"),
                        rs.getInt("min_membership_months"),
                        rs.getInt("monthly_reward_points"),
                        rs.getBigDecimal("insurance_benefit_amount"),
                        rs.getBoolean("active"),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at"))));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public Optional<LoyaltyMonthlyRewardView> getMonthlyReward(UUID loyaltyId, String periodMonth, ScopeContext scope) {
        if (loyaltyId == null || periodMonth == null) {
            return Optional.empty();
        }
        List<LoyaltyMonthlyRewardView> list = jdbc.query(
                """
                SELECT reward_id, owner_entity_id, loyalty_id, customer_id, period_month,
                       qualifying_spend, spend_threshold, membership_months, min_membership_months,
                       points_awarded, insurance_eligible, reward_status, disqualification_reason,
                       evaluated_at
                  FROM customers.loyalty_monthly_reward
                 WHERE loyalty_id = ? AND period_month = ?
                """,
                (rs, rowNum) -> mapRewardRow(rs),
                loyaltyId,
                periodMonth);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<LoyaltyMonthlyRewardView> listMonthlyRewards(UUID loyaltyId, ScopeContext scope) {
        if (loyaltyId == null) {
            return Collections.emptyList();
        }
        return jdbc.query(
                """
                SELECT reward_id, owner_entity_id, loyalty_id, customer_id, period_month,
                       qualifying_spend, spend_threshold, membership_months, min_membership_months,
                       points_awarded, insurance_eligible, reward_status, disqualification_reason,
                       evaluated_at
                  FROM customers.loyalty_monthly_reward
                 WHERE loyalty_id = ?
                 ORDER BY period_month DESC
                """,
                (rs, rowNum) -> mapRewardRow(rs),
                loyaltyId);
    }

    @Override
    public LoyaltyInsuranceEligibilityView evaluateInsuranceEligibility(
            UUID loyaltyId, LocalDate asOfDate, ScopeContext scope) {
        if (loyaltyId == null) {
            return new LoyaltyInsuranceEligibilityView(
                    null,
                    null,
                    false,
                    "LOYALTY_CUSTOMER_NOT_FOUND",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    0,
                    BigDecimal.ZERO,
                    asOfDate != null ? asOfDate : LocalDate.now(clock));
        }
        LocalDate date = asOfDate != null ? asOfDate : LocalDate.now(clock);

        // 1. Fetch loyalty customer
        Map<String, Object> customer = jdbc.query(
                "SELECT customer_id, enrolled_at, status FROM customers.loyalty_customer WHERE loyalty_id = ?",
                rs -> rs.next()
                        ? Map.of(
                                "customer_id", rs.getObject("customer_id"),
                                "enrolled_at", rs.getTimestamp("enrolled_at"),
                                "status", rs.getString("status"))
                        : null,
                loyaltyId);

        if (customer == null) {
            return new LoyaltyInsuranceEligibilityView(
                    loyaltyId,
                    null,
                    false,
                    "LOYALTY_CUSTOMER_NOT_FOUND",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    0,
                    BigDecimal.ZERO,
                    date);
        }
        if (!"ACTIVE".equals(customer.get("status"))) {
            return new LoyaltyInsuranceEligibilityView(
                    loyaltyId,
                    (UUID) customer.get("customer_id"),
                    false,
                    "CUSTOMER_INACTIVE",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    0,
                    0,
                    BigDecimal.ZERO,
                    date);
        }

        UUID customerId = (UUID) customer.get("customer_id");
        Timestamp enrolledTs = (Timestamp) customer.get("enrolled_at");
        LocalDate enrolledDate =
                enrolledTs != null ? enrolledTs.toLocalDateTime().toLocalDate() : date;

        // 2. Resolve policy
        Optional<LoyaltyRewardPolicyView> activePolicy = getActiveLoyaltyPolicy(scope);
        BigDecimal spendThreshold;
        int minMonths;
        BigDecimal benefitAmount;

        if (activePolicy.isPresent()) {
            spendThreshold = activePolicy.get().monthlySpendThreshold();
            minMonths = activePolicy.get().minMembershipMonths();
            benefitAmount = activePolicy.get().insuranceBenefitAmount();
        } else {
            spendThreshold = config.get("customers.loyalty.monthly_spend_threshold", scope)
                    .map(BigDecimal::new)
                    .orElse(new BigDecimal("20000.00"));
            minMonths = config.getInt("customers.loyalty.min_membership_months", scope, 3);
            benefitAmount = config.get("customers.loyalty.insurance_benefit_amount", scope)
                    .map(BigDecimal::new)
                    .orElse(new BigDecimal("50000.00"));
        }

        // 3. Compute tenure & spend
        int membershipMonths = (int) ChronoUnit.MONTHS.between(enrolledDate, date);
        if (membershipMonths < 0) {
            membershipMonths = 0;
        }

        YearMonth ym = YearMonth.from(date);
        BigDecimal monthlySpend = getMonthlyQualifyingSpend(loyaltyId, ym.toString(), scope);

        // Also check if evaluated monthly reward exists
        Optional<LoyaltyMonthlyRewardView> monthlyReward = getMonthlyReward(loyaltyId, ym.toString(), scope);
        boolean tenureOk = membershipMonths >= minMonths;
        boolean spendOk = monthlySpend.compareTo(spendThreshold) >= 0;
        boolean eligible =
                (monthlyReward.isPresent() && monthlyReward.get().insuranceEligible()) || (tenureOk && spendOk);

        String reason = eligible ? "ELIGIBLE" : (!tenureOk ? "TENURE_TOO_SHORT" : "INSUFFICIENT_SPEND");

        return new LoyaltyInsuranceEligibilityView(
                loyaltyId,
                customerId,
                eligible,
                reason,
                monthlySpend,
                spendThreshold,
                membershipMonths,
                minMonths,
                benefitAmount,
                date);
    }

    @Override
    public Optional<LoyaltyInsuranceClaimView> getInsuranceClaim(UUID claimId, ScopeContext scope) {
        if (claimId == null) {
            return Optional.empty();
        }
        List<LoyaltyInsuranceClaimView> list = jdbc.query(
                """
                SELECT claim_id, owner_entity_id, loyalty_id, customer_id, claim_type,
                       claim_status, policy_benefit_amount, incident_date, claimant_relationship,
                       supporting_document_ref, eligibility_verified, eligibility_snapshot,
                       rejection_reason, submitted_at, updated_at
                  FROM customers.loyalty_insurance_claim
                 WHERE claim_id = ?
                """,
                (rs, rowNum) -> mapClaimRow(rs),
                claimId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    @Override
    public List<LoyaltyInsuranceClaimView> listInsuranceClaims(UUID loyaltyId, ScopeContext scope) {
        if (loyaltyId == null) {
            return Collections.emptyList();
        }
        return jdbc.query(
                """
                SELECT claim_id, owner_entity_id, loyalty_id, customer_id, claim_type,
                       claim_status, policy_benefit_amount, incident_date, claimant_relationship,
                       supporting_document_ref, eligibility_verified, eligibility_snapshot,
                       rejection_reason, submitted_at, updated_at
                  FROM customers.loyalty_insurance_claim
                 WHERE loyalty_id = ?
                 ORDER BY submitted_at DESC
                """,
                (rs, rowNum) -> mapClaimRow(rs),
                loyaltyId);
    }

    @Override
    public int getLoyaltyPointsBalance(UUID loyaltyId, ScopeContext scope) {
        if (loyaltyId == null) {
            return 0;
        }
        Integer balance = jdbc.queryForObject(
                "SELECT COALESCE(SUM(points), 0) FROM customers.loyalty_points_ledger WHERE loyalty_id = ?",
                Integer.class,
                loyaltyId);
        return balance != null ? balance : 0;
    }

    @Override
    public BigDecimal getMonthlyQualifyingSpend(UUID loyaltyId, String periodMonth, ScopeContext scope) {
        if (loyaltyId == null || periodMonth == null || !periodMonth.matches("^\\d{4}-\\d{2}$")) {
            return BigDecimal.ZERO;
        }
        YearMonth ym = YearMonth.parse(periodMonth);
        BigDecimal spend = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount), 0)
                  FROM customers.loyalty_spend
                 WHERE loyalty_id = ?
                   AND spend_date >= ?
                   AND spend_date <= ?
                """,
                BigDecimal.class,
                loyaltyId,
                Date.valueOf(ym.atDay(1)),
                Date.valueOf(ym.atEndOfMonth()));
        return spend != null ? spend : BigDecimal.ZERO;
    }

    private LoyaltyMonthlyRewardView mapRewardRow(ResultSet rs) throws SQLException {
        return new LoyaltyMonthlyRewardView(
                rs.getObject("reward_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("loyalty_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("period_month"),
                rs.getBigDecimal("qualifying_spend"),
                rs.getBigDecimal("spend_threshold"),
                rs.getInt("membership_months"),
                rs.getInt("min_membership_months"),
                rs.getInt("points_awarded"),
                rs.getBoolean("insurance_eligible"),
                rs.getString("reward_status"),
                rs.getString("disqualification_reason"),
                toInstant(rs.getTimestamp("evaluated_at")));
    }

    private LoyaltyInsuranceClaimView mapClaimRow(ResultSet rs) throws SQLException {
        return new LoyaltyInsuranceClaimView(
                rs.getObject("claim_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getObject("loyalty_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("claim_type"),
                rs.getString("claim_status"),
                rs.getBigDecimal("policy_benefit_amount"),
                rs.getDate("incident_date").toLocalDate(),
                rs.getString("claimant_relationship"),
                rs.getString("supporting_document_ref"),
                rs.getBoolean("eligibility_verified"),
                rs.getString("eligibility_snapshot"),
                rs.getString("rejection_reason"),
                toInstant(rs.getTimestamp("submitted_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private LoyaltyEligibility evaluateEligibility(LoyaltyCustomerView customer, UUID locationId) {
        if (!"ACTIVE".equals(customer.status())) {
            return LoyaltyEligibility.inactive(customer, locationId);
        }
        if (locationId == null || customer.allLocationsPermitted()) {
            return LoyaltyEligibility.eligible(customer, locationId);
        }
        if (locationId.equals(customer.registeredLocationId())
                || (customer.permittedLocationIds() != null
                        && customer.permittedLocationIds().contains(locationId))) {
            return LoyaltyEligibility.eligible(customer, locationId);
        }
        return LoyaltyEligibility.locationNotPermitted(customer, locationId);
    }

    private String baseSelect() {
        return """
                SELECT lc.loyalty_id,
                       lc.customer_id,
                       lc.owner_entity_id,
                       c.display_name,
                       c.display_name_si,
                       c.display_name_ta,
                       ph.phone,
                       c.nic_last4,
                       lc.registered_location_id,
                       lc.region_code,
                       lc.regional_member_no,
                       lc.loyalty_tier,
                       lc.status,
                       lc.all_locations_permitted,
                       lc.regional_attributes::text as regional_attributes,
                       lc.enrolled_at,
                       lc.updated_at
                  FROM customers.loyalty_customer lc
                  JOIN customers.customer c ON c.customer_id = lc.customer_id
             LEFT JOIN customers.customer_phone ph ON ph.customer_id = c.customer_id AND ph.is_primary = true
                """;
    }

    private LoyaltyCustomerView mapRow(ResultSet rs) throws SQLException {
        UUID loyaltyId = rs.getObject("loyalty_id", UUID.class);
        Set<UUID> permittedLocations = loadPermittedLocations(loyaltyId);
        Map<String, String> attributes = parseAttributes(rs.getString("regional_attributes"));

        return new LoyaltyCustomerView(
                loyaltyId,
                rs.getObject("customer_id", UUID.class),
                rs.getObject("owner_entity_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("display_name_si"),
                rs.getString("display_name_ta"),
                rs.getString("phone"),
                rs.getString("nic_last4"),
                rs.getObject("registered_location_id", UUID.class),
                rs.getString("region_code"),
                rs.getString("regional_member_no"),
                rs.getString("loyalty_tier"),
                rs.getString("status"),
                rs.getBoolean("all_locations_permitted"),
                permittedLocations,
                attributes,
                toInstant(rs.getTimestamp("enrolled_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private Set<UUID> loadPermittedLocations(UUID loyaltyId) {
        List<UUID> locs = jdbc.query(
                "SELECT permitted_location_id FROM customers.loyalty_permitted_location WHERE loyalty_id = ? AND revoked_at IS NULL",
                (rs, rowNum) -> rs.getObject("permitted_location_id", UUID.class),
                loyaltyId);
        return new HashSet<>(locs);
    }

    private Map<String, String> parseAttributes(String jsonText) {
        if (jsonText == null || jsonText.isBlank() || "{}".equals(jsonText.trim())) {
            return Collections.emptyMap();
        }
        try {
            return json.readValue(jsonText, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
