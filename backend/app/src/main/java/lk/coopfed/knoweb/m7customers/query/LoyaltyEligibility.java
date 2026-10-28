package lk.coopfed.knoweb.m7customers.query;

import java.util.UUID;

/**
 * Result of loyalty eligibility evaluation for a customer at a given location/branch (PGL-M3-01).
 */
public record LoyaltyEligibility(
        boolean eligible,
        String status,
        String reason,
        UUID customerId,
        UUID locationId,
        String loyaltyTier,
        LoyaltyCustomerView customer) {

    public static LoyaltyEligibility eligible(LoyaltyCustomerView customer, UUID locationId) {
        return new LoyaltyEligibility(
                true,
                customer.status(),
                "ELIGIBLE",
                customer.customerId(),
                locationId,
                customer.loyaltyTier(),
                customer);
    }

    public static LoyaltyEligibility locationNotPermitted(LoyaltyCustomerView customer, UUID locationId) {
        return new LoyaltyEligibility(
                false,
                customer.status(),
                "LOCATION_NOT_PERMITTED",
                customer.customerId(),
                locationId,
                customer.loyaltyTier(),
                customer);
    }

    public static LoyaltyEligibility inactive(LoyaltyCustomerView customer, UUID locationId) {
        return new LoyaltyEligibility(
                false,
                customer.status(),
                "CUSTOMER_INACTIVE",
                customer.customerId(),
                locationId,
                customer.loyaltyTier(),
                customer);
    }

    public static LoyaltyEligibility notFound(UUID locationId) {
        return new LoyaltyEligibility(false, "NOT_FOUND", "CUSTOMER_NOT_FOUND", null, locationId, null, null);
    }
}
