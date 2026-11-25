package lk.coopfed.knoweb.m7customers.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * On-demand evaluation view of a customer's insurance benefit eligibility as of a date (PGL-M3-03).
 */
public record LoyaltyInsuranceEligibilityView(
        UUID loyaltyId,
        UUID customerId,
        boolean eligible,
        String reason,
        BigDecimal monthlyQualifyingSpend,
        BigDecimal spendThreshold,
        int membershipMonths,
        int minMembershipMonths,
        BigDecimal insuranceBenefitAmount,
        LocalDate evaluatedAsOf) {}
