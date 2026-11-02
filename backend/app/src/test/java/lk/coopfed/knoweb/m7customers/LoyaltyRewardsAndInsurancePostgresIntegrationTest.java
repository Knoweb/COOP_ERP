package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SOCIETY;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.other;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.till;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m7customers.api.CalculateMonthlyLoyaltyRewards;
import lk.coopfed.knoweb.m7customers.api.ConfigureLoyaltyPolicy;
import lk.coopfed.knoweb.m7customers.api.LoyaltyInsuranceClaimStatusUpdated;
import lk.coopfed.knoweb.m7customers.api.LoyaltyInsuranceClaimSubmitted;
import lk.coopfed.knoweb.m7customers.api.LoyaltyMonthlyRewardCalculated;
import lk.coopfed.knoweb.m7customers.api.LoyaltyPolicyConfigured;
import lk.coopfed.knoweb.m7customers.api.RecordLoyaltySpend;
import lk.coopfed.knoweb.m7customers.api.RegisterLoyaltyCustomer;
import lk.coopfed.knoweb.m7customers.api.SubmitInsuranceClaim;
import lk.coopfed.knoweb.m7customers.api.UpdateInsuranceClaimStatus;
import lk.coopfed.knoweb.m7customers.internal.loyalty.CalculateMonthlyLoyaltyRewardsHandler;
import lk.coopfed.knoweb.m7customers.internal.loyalty.ConfigureLoyaltyPolicyHandler;
import lk.coopfed.knoweb.m7customers.internal.loyalty.SubmitInsuranceClaimHandler;
import lk.coopfed.knoweb.m7customers.internal.loyalty.UpdateInsuranceClaimStatusHandler;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerQueries;
import lk.coopfed.knoweb.m7customers.query.LoyaltyInsuranceClaimView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyInsuranceEligibilityView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyMonthlyRewardView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyRewardPolicyView;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests proving loyalty monthly reward calculation, configurable policies,
 * death-benefit insurance eligibility workflow, and absence of automatic cash payouts (PGL-M3-03).
 */
class LoyaltyRewardsAndInsurancePostgresIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    Handles<ConfigureLoyaltyPolicy, UUID> configurePolicy;

    @Autowired
    Handles<RegisterLoyaltyCustomer, UUID> registerLoyalty;

    @Autowired
    Handles<RecordLoyaltySpend, UUID> recordSpend;

    @Autowired
    Handles<CalculateMonthlyLoyaltyRewards, List<UUID>> calculateRewards;

    @Autowired
    Handles<SubmitInsuranceClaim, UUID> submitClaim;

    @Autowired
    Handles<UpdateInsuranceClaimStatus, Void> updateClaimStatus;

    @Autowired
    LoyaltyCustomerQueries loyaltyQueries;

    @BeforeEach
    void setup() {
        CustomersFixture.arrange(superuserJdbc());
        kernel.reset();
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void configurablePolicyManagementAndAudit() {
        // 1. Configure policy with requested parameters: Rs. 20,000 threshold, 3+ months age, 200 points, Rs. 50,000
        // benefit
        ConfigureLoyaltyPolicy cmd = new ConfigureLoyaltyPolicy(
                "POLGOLLA_DEFAULT", new BigDecimal("20000.00"), 3, 200, new BigDecimal("50000.00"), true);

        UUID policyId = configurePolicy.handle(cmd, office());
        assertThat(policyId).isNotNull();

        // 2. Query active policy
        Optional<LoyaltyRewardPolicyView> policyOpt = loyaltyQueries.getActiveLoyaltyPolicy(office());
        assertThat(policyOpt).isPresent();
        LoyaltyRewardPolicyView policy = policyOpt.get();
        assertThat(policy.policyId()).isEqualTo(policyId);
        assertThat(policy.policyName()).isEqualTo("POLGOLLA_DEFAULT");
        assertThat(policy.monthlySpendThreshold()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(policy.minMembershipMonths()).isEqualTo(3);
        assertThat(policy.monthlyRewardPoints()).isEqualTo(200);
        assertThat(policy.insuranceBenefitAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(policy.active()).isTrue();

        // 3. Verify audit and events have no PII
        var audits = kernel.committedAudit();
        assertThat(audits).anySatisfy(a -> {
            assertThat(a.eventType()).isEqualTo(ConfigureLoyaltyPolicyHandler.AUDIT_POLICY_CONFIGURED);
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) a.after();
            assertThat(after).containsEntry("policy_name", "POLGOLLA_DEFAULT");
            assertThat(after).containsEntry("monthly_spend_threshold", "20000.00");
            assertThat(after).containsEntry("min_membership_months", 3);
            assertThat(after).containsEntry("monthly_reward_points", 200);
            assertThat(after).containsEntry("insurance_benefit_amount", "50000.00");
            assertThat(after.keySet()).doesNotContain("phone", "nic", "name");
        });

        var events = kernel.committedEvents();
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(LoyaltyPolicyConfigured.class);
            LoyaltyPolicyConfigured ev = (LoyaltyPolicyConfigured) e;
            assertThat(ev.policyId()).isEqualTo(policyId);
            assertThat(ev.ownerEntityId()).isEqualTo(SOCIETY);
            assertThat(ev.monthlySpendThreshold()).isEqualByComparingTo(new BigDecimal("20000.00"));
            assertThat(ev.minMembershipMonths()).isEqualTo(3);
            assertThat(ev.monthlyRewardPoints()).isEqualTo(200);
            assertThat(ev.insuranceBenefitAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        });
    }

    @Test
    void monthlyQualifyingSpendAndRewardCalculation() {
        // Setup configurable policy: 20,000 threshold, 3 months tenure, 200 points, 50,000 benefit
        configurePolicy.handle(
                new ConfigureLoyaltyPolicy(
                        "DEFAULT", new BigDecimal("20000.00"), 3, 200, new BigDecimal("50000.00"), true),
                office());

        // Enroll Customer A (simulate tenure = 4 months by setting enrolled_at in past)
        UUID loyaltyA = registerLoyalty.handle(
                new RegisterLoyaltyCustomer(
                        null,
                        "Customer Alpha",
                        null,
                        null,
                        "en",
                        "0700000111",
                        "199011112222",
                        SHOP,
                        "POL-1",
                        "MEM-001",
                        "STANDARD",
                        true,
                        Set.of(),
                        Map.of()),
                office());
        UUID customerA = loyaltyQueries
                .lookupByPhone("0700000111", office())
                .orElseThrow()
                .customerId();
        superuserJdbc()
                .update(
                        "UPDATE customers.loyalty_customer SET enrolled_at = ? WHERE loyalty_id = ?",
                        Timestamp.valueOf(LocalDate.of(2026, 6, 1).atStartOfDay()),
                        loyaltyA);

        // Enroll Customer B (simulate tenure = 1 month < 3 months)
        UUID loyaltyB = registerLoyalty.handle(
                new RegisterLoyaltyCustomer(
                        null,
                        "Customer Beta",
                        null,
                        null,
                        "en",
                        "0700000222",
                        "199122223333",
                        SHOP,
                        "POL-1",
                        "MEM-002",
                        "STANDARD",
                        true,
                        Set.of(),
                        Map.of()),
                office());
        UUID customerB = loyaltyQueries
                .lookupByPhone("0700000222", office())
                .orElseThrow()
                .customerId();
        superuserJdbc()
                .update(
                        "UPDATE customers.loyalty_customer SET enrolled_at = ? WHERE loyalty_id = ?",
                        Timestamp.valueOf(LocalDate.of(2026, 9, 20).atStartOfDay()),
                        loyaltyB);

        // Enroll Customer C (tenure = 5 months >= 3 months, but will spend < 20,000)
        UUID loyaltyC = registerLoyalty.handle(
                new RegisterLoyaltyCustomer(
                        null,
                        "Customer Gamma",
                        null,
                        null,
                        "en",
                        "0700000333",
                        "199233334444",
                        SHOP,
                        "POL-1",
                        "MEM-003",
                        "STANDARD",
                        true,
                        Set.of(),
                        Map.of()),
                office());
        UUID customerC = loyaltyQueries
                .lookupByPhone("0700000333", office())
                .orElseThrow()
                .customerId();
        superuserJdbc()
                .update(
                        "UPDATE customers.loyalty_customer SET enrolled_at = ? WHERE loyalty_id = ?",
                        Timestamp.valueOf(LocalDate.of(2026, 5, 1).atStartOfDay()),
                        loyaltyC);

        // Record Spends in October 2026:
        // Customer A: 12,000 + 13,000 = 25,000 (>= 20,000 threshold)
        recordSpend.handle(
                new RecordLoyaltySpend(loyaltyA, SHOP, LocalDate.of(2026, 10, 5), new BigDecimal("12000.00"), "RCP-A1"),
                till());
        recordSpend.handle(
                new RecordLoyaltySpend(
                        loyaltyA, SHOP, LocalDate.of(2026, 10, 20), new BigDecimal("13000.00"), "RCP-A2"),
                till());

        // Customer B: 25,000 in October (spend met, but tenure 1 month < 3 months)
        recordSpend.handle(
                new RecordLoyaltySpend(
                        loyaltyB, SHOP, LocalDate.of(2026, 10, 12), new BigDecimal("25000.00"), "RCP-B1"),
                till());

        // Customer C: 15,000 in October (tenure met, but spend 15,000 < 20,000)
        recordSpend.handle(
                new RecordLoyaltySpend(
                        loyaltyC, SHOP, LocalDate.of(2026, 10, 18), new BigDecimal("15000.00"), "RCP-C1"),
                till());

        kernel.reset();

        // Run monthly loyalty calculation for period 2026-10
        List<UUID> rewards = calculateRewards.handle(new CalculateMonthlyLoyaltyRewards("2026-10", null), office());
        assertThat(rewards).hasSize(3);

        // 1. Check Customer A: AWARDED 200 points, insurance eligible = true
        List<LoyaltyMonthlyRewardView> aRewards = loyaltyQueries.listMonthlyRewards(loyaltyA, office());
        assertThat(aRewards).hasSize(1);
        LoyaltyMonthlyRewardView aReward = aRewards.get(0);
        assertThat(aReward.periodMonth()).isEqualTo("2026-10");
        assertThat(aReward.qualifyingSpend()).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(aReward.pointsAwarded()).isEqualTo(200);
        assertThat(aReward.insuranceEligible()).isTrue();
        assertThat(aReward.rewardStatus()).isEqualTo("AWARDED");
        assertThat(aReward.disqualificationReason()).isNull();

        // Points balance recomputed dynamically from insert-only points ledger:
        assertThat(loyaltyQueries.getLoyaltyPointsBalance(loyaltyA, office())).isEqualTo(200);

        // Insurance eligibility view:
        LoyaltyInsuranceEligibilityView aElig =
                loyaltyQueries.evaluateInsuranceEligibility(loyaltyA, LocalDate.of(2026, 10, 31), office());
        assertThat(aElig.eligible()).isTrue();
        assertThat(aElig.insuranceBenefitAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(aElig.membershipMonths()).isGreaterThanOrEqualTo(3);

        // 2. Check Customer B: DISQUALIFIED due to tenure too short (0 points)
        List<LoyaltyMonthlyRewardView> bRewards = loyaltyQueries.listMonthlyRewards(loyaltyB, office());
        assertThat(bRewards).hasSize(1);
        LoyaltyMonthlyRewardView bReward = bRewards.get(0);
        assertThat(bReward.pointsAwarded()).isEqualTo(0);
        assertThat(bReward.insuranceEligible()).isFalse();
        assertThat(bReward.rewardStatus()).isEqualTo("DISQUALIFIED");
        assertThat(bReward.disqualificationReason()).isEqualTo("TENURE_TOO_SHORT");
        assertThat(loyaltyQueries.getLoyaltyPointsBalance(loyaltyB, office())).isEqualTo(0);

        // 3. Check Customer C: DISQUALIFIED due to insufficient spend (0 points)
        List<LoyaltyMonthlyRewardView> cRewards = loyaltyQueries.listMonthlyRewards(loyaltyC, office());
        assertThat(cRewards).hasSize(1);
        LoyaltyMonthlyRewardView cReward = cRewards.get(0);
        assertThat(cReward.pointsAwarded()).isEqualTo(0);
        assertThat(cReward.insuranceEligible()).isFalse();
        assertThat(cReward.rewardStatus()).isEqualTo("DISQUALIFIED");
        assertThat(cReward.disqualificationReason()).isEqualTo("INSUFFICIENT_SPEND");
        assertThat(loyaltyQueries.getLoyaltyPointsBalance(loyaltyC, office())).isEqualTo(0);

        // 4. Verify audit & events for monthly reward calculation
        var audits = kernel.committedAudit();
        assertThat(audits)
                .filteredOn(a ->
                        a.eventType().equals(CalculateMonthlyLoyaltyRewardsHandler.AUDIT_MONTHLY_REWARD_CALCULATED))
                .hasSize(3);

        var events = kernel.committedEvents();
        assertThat(events)
                .filteredOn(e -> e instanceof LoyaltyMonthlyRewardCalculated)
                .hasSize(3);
    }

    @Test
    void insuranceDeathBenefitWorkflowWithoutCashPayout() {
        // Setup policy
        configurePolicy.handle(
                new ConfigureLoyaltyPolicy(
                        "DEFAULT", new BigDecimal("20000.00"), 3, 200, new BigDecimal("50000.00"), true),
                office());

        // Enroll Customer
        UUID loyaltyId = registerLoyalty.handle(
                new RegisterLoyaltyCustomer(
                        null,
                        "Silva Perera",
                        null,
                        null,
                        "si",
                        "0700000444",
                        "198500001234",
                        SHOP,
                        "POL-1",
                        "MEM-004",
                        "STANDARD",
                        true,
                        Set.of(),
                        Map.of()),
                office());
        UUID customerId = loyaltyQueries
                .lookupByPhone("0700000444", office())
                .orElseThrow()
                .customerId();
        superuserJdbc()
                .update(
                        "UPDATE customers.loyalty_customer SET enrolled_at = ? WHERE loyalty_id = ?",
                        Timestamp.valueOf(LocalDate.of(2026, 4, 1).atStartOfDay()),
                        loyaltyId);

        // Record qualifying spend in October 2026: 22,000 >= 20,000
        recordSpend.handle(
                new RecordLoyaltySpend(
                        loyaltyId, SHOP, LocalDate.of(2026, 10, 10), new BigDecimal("22000.00"), "RCP-D1"),
                till());

        // Run monthly reward calculation
        calculateRewards.handle(new CalculateMonthlyLoyaltyRewards("2026-10", customerId), office());

        kernel.reset();

        // 1. Submit Death-Benefit Claim
        LocalDate incidentDate = LocalDate.of(2026, 10, 24);
        SubmitInsuranceClaim submitCmd =
                new SubmitInsuranceClaim(loyaltyId, "DEATH_BENEFIT", incidentDate, "SPOUSE", "DOC-REF-DEATH-CERT-7788");

        UUID claimId = submitClaim.handle(submitCmd, office());
        assertThat(claimId).isNotNull();

        // 2. Query claim details: status ELIGIBLE_SUPPORTED, benefit amount 50,000, supporting document recorded
        Optional<LoyaltyInsuranceClaimView> claimOpt = loyaltyQueries.getInsuranceClaim(claimId, office());
        assertThat(claimOpt).isPresent();
        LoyaltyInsuranceClaimView claim = claimOpt.get();
        assertThat(claim.claimId()).isEqualTo(claimId);
        assertThat(claim.loyaltyId()).isEqualTo(loyaltyId);
        assertThat(claim.customerId()).isEqualTo(customerId);
        assertThat(claim.claimType()).isEqualTo("DEATH_BENEFIT");
        assertThat(claim.claimStatus()).isEqualTo("ELIGIBLE_SUPPORTED");
        assertThat(claim.policyBenefitAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(claim.incidentDate()).isEqualTo(incidentDate);
        assertThat(claim.claimantRelationship()).isEqualTo("SPOUSE");
        assertThat(claim.supportingDocumentRef()).isEqualTo("DOC-REF-DEATH-CERT-7788");
        assertThat(claim.eligibilityVerified()).isTrue();
        assertThat(claim.eligibilitySnapshotJson()).contains("22000.00");
        assertThat(claim.eligibilitySnapshotJson()).contains("50000.00");

        // 3. Move claim to APPROVED status
        updateClaimStatus.handle(new UpdateInsuranceClaimStatus(claimId, "APPROVED", null), office());

        LoyaltyInsuranceClaimView approvedClaim =
                loyaltyQueries.getInsuranceClaim(claimId, office()).orElseThrow();
        assertThat(approvedClaim.claimStatus()).isEqualTo("APPROVED");

        // 4. Invariant: STRICTLY NO automatic cash payout or accounting posting occurs!
        Integer customerPostingCount = superuserJdbc()
                .queryForObject(
                        "SELECT COUNT(*) FROM customers.account_posting WHERE customer_id = ?",
                        Integer.class,
                        customerId);
        assertThat(customerPostingCount).isZero();

        Integer customerPaymentCount = superuserJdbc()
                .queryForObject(
                        "SELECT COUNT(*) FROM customers.doc_customer_payment WHERE customer_id = ?",
                        Integer.class,
                        customerId);
        assertThat(customerPaymentCount).isZero();

        // 5. Test rejection guard: status REJECTED requires a non-blank rejection reason
        assertThatThrownBy(() ->
                        updateClaimStatus.handle(new UpdateInsuranceClaimStatus(claimId, "REJECTED", "   "), office()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.claim_rejection_reason_required");

        // 6. Verify audit & events have NO PII
        var audits = kernel.committedAudit();
        assertThat(audits).anySatisfy(a -> {
            assertThat(a.eventType()).isEqualTo(SubmitInsuranceClaimHandler.AUDIT_CLAIM_SUBMITTED);
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) a.after();
            assertThat(after).containsEntry("claim_type", "DEATH_BENEFIT");
            assertThat(after).containsEntry("claim_status", "ELIGIBLE_SUPPORTED");
            assertThat(after).containsEntry("policy_benefit_amount", "50000.00");
            assertThat(after.keySet()).doesNotContain("phone", "nic", "name", "claimant");
        });

        assertThat(audits).anySatisfy(a -> {
            assertThat(a.eventType()).isEqualTo(UpdateInsuranceClaimStatusHandler.AUDIT_STATUS_UPDATED);
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) a.after();
            assertThat(after).containsEntry("claim_status", "APPROVED");
        });

        var events = kernel.committedEvents();
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(LoyaltyInsuranceClaimSubmitted.class);
            LoyaltyInsuranceClaimSubmitted ev = (LoyaltyInsuranceClaimSubmitted) e;
            assertThat(ev.claimId()).isEqualTo(claimId);
            assertThat(ev.claimType()).isEqualTo("DEATH_BENEFIT");
            assertThat(ev.claimStatus()).isEqualTo("ELIGIBLE_SUPPORTED");
            assertThat(ev.policyBenefitAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        });

        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(LoyaltyInsuranceClaimStatusUpdated.class);
            LoyaltyInsuranceClaimStatusUpdated ev = (LoyaltyInsuranceClaimStatusUpdated) e;
            assertThat(ev.claimId()).isEqualTo(claimId);
            assertThat(ev.newStatus()).isEqualTo("APPROVED");
        });
    }

    @Test
    void crossEntityIsolationEnforcedByRls() {
        // Setup policy and customer in SOCIETY
        configurePolicy.handle(
                new ConfigureLoyaltyPolicy(
                        "DEFAULT", new BigDecimal("20000.00"), 3, 200, new BigDecimal("50000.00"), true),
                office());

        UUID loyaltyId = registerLoyalty.handle(
                new RegisterLoyaltyCustomer(
                        null,
                        "Customer Cross",
                        null,
                        null,
                        "en",
                        "0700000555",
                        "198899990000",
                        SHOP,
                        "POL-1",
                        "MEM-005",
                        "STANDARD",
                        true,
                        Set.of(),
                        Map.of()),
                office());

        recordSpend.handle(
                new RecordLoyaltySpend(
                        loyaltyId, SHOP, LocalDate.of(2026, 10, 10), new BigDecimal("25000.00"), "RCP-X1"),
                till());

        calculateRewards.handle(new CalculateMonthlyLoyaltyRewards("2026-10", null), office());

        UUID claimId = submitClaim.handle(
                new SubmitInsuranceClaim(loyaltyId, "DEATH_BENEFIT", LocalDate.of(2026, 10, 20), "SPOUSE", "DOC-005"),
                office());

        // OTHER entity attempts to read SOCIETY's data:
        // 1. Policy query from OTHER returns empty
        Optional<LoyaltyRewardPolicyView> otherPolicy = loyaltyQueries.getActiveLoyaltyPolicy(other());
        assertThat(otherPolicy).isEmpty();

        // 2. Monthly rewards list from OTHER returns empty
        List<LoyaltyMonthlyRewardView> otherRewards = loyaltyQueries.listMonthlyRewards(loyaltyId, other());
        assertThat(otherRewards).isEmpty();

        // 3. Points balance query from OTHER returns 0
        assertThat(loyaltyQueries.getLoyaltyPointsBalance(loyaltyId, other())).isEqualTo(0);

        // 4. Insurance claim query from OTHER returns empty
        Optional<LoyaltyInsuranceClaimView> otherClaim = loyaltyQueries.getInsuranceClaim(claimId, other());
        assertThat(otherClaim).isEmpty();

        // 5. OTHER attempts to update SOCIETY's claim: denied (not found under RLS)
        assertThatThrownBy(() ->
                        updateClaimStatus.handle(new UpdateInsuranceClaimStatus(claimId, "APPROVED", null), other()))
                .isInstanceOf(ProblemException.class)
                .hasMessageContaining("m7.loyalty.claim_not_found");
    }
}
