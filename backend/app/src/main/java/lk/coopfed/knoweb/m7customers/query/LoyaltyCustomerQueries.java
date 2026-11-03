package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Stable query seam for centralized loyalty customer identity, eligibility, monthly rewards,
 * and insurance claim support (PGL-M3-01, PGL-M3-03).
 * Read access is row-level secured by owner entity and permitted branch locations.
 * Safe for boundary-clean consumption by pricing (M1-06) and migration (M2-05).
 */
public interface LoyaltyCustomerQueries {

    /** Looks up an active loyalty customer by primary mobile phone number. */
    Optional<LoyaltyCustomerView> lookupByPhone(String phone, ScopeContext scope);

    /** Looks up an active loyalty customer by regional society membership number. */
    Optional<LoyaltyCustomerView> lookupByMemberNo(String memberNo, ScopeContext scope);

    /** Looks up an active loyalty customer by National Identity Card (NIC). */
    Optional<LoyaltyCustomerView> lookupByNic(String nic, ScopeContext scope);

    /** Gets a loyalty customer by customer ID. */
    Optional<LoyaltyCustomerView> getLoyaltyCustomer(UUID customerId, ScopeContext scope);

    /** Evaluates loyalty eligibility for a customer at the specified branch/location. */
    LoyaltyEligibility checkEligibility(UUID customerId, UUID locationId, ScopeContext scope);

    /** Evaluates loyalty eligibility by any identifier (UUID, member number, phone, or NIC) at the specified branch/location. */
    LoyaltyEligibility checkEligibility(String identifier, UUID locationId, ScopeContext scope);

    /** Lists loyalty customers registered under a given region code. */
    List<LoyaltyCustomerView> listByRegion(String regionCode, ScopeContext scope);

    // --- PGL-M3-03: Monthly Rewards & Insurance Eligibility Queries ---

    /** Gets the entity's active loyalty reward and insurance policy. */
    Optional<LoyaltyRewardPolicyView> getActiveLoyaltyPolicy(ScopeContext scope);

    /** Gets the monthly evaluation and reward record for a customer in a given month YYYY-MM. */
    Optional<LoyaltyMonthlyRewardView> getMonthlyReward(UUID loyaltyId, String periodMonth, ScopeContext scope);

    /** Lists monthly evaluation and reward records for a customer. */
    List<LoyaltyMonthlyRewardView> listMonthlyRewards(UUID loyaltyId, ScopeContext scope);

    /** Evaluates insurance benefit eligibility for a customer as of a given date. */
    LoyaltyInsuranceEligibilityView evaluateInsuranceEligibility(
            UUID loyaltyId, LocalDate asOfDate, ScopeContext scope);

    /** Gets an insurance claim by claim ID. */
    Optional<LoyaltyInsuranceClaimView> getInsuranceClaim(UUID claimId, ScopeContext scope);

    /** Lists insurance claims for a loyalty customer. */
    List<LoyaltyInsuranceClaimView> listInsuranceClaims(UUID loyaltyId, ScopeContext scope);

    /** Gets the accumulated loyalty reward points balance for a customer. */
    int getLoyaltyPointsBalance(UUID loyaltyId, ScopeContext scope);

    /** Gets the customer's total qualifying spend for a calendar month YYYY-MM. */
    BigDecimal getMonthlyQualifyingSpend(UUID loyaltyId, String periodMonth, ScopeContext scope);
}
