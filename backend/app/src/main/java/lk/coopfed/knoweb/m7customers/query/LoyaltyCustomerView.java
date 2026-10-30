package lk.coopfed.knoweb.m7customers.query;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public view of a centralized loyalty customer with regional registration details (PGL-M3-01).
 * Safe for cross-module consumption by pricing (M1-06) and migration (M2-05).
 */
public record LoyaltyCustomerView(
        UUID loyaltyId,
        UUID customerId,
        UUID ownerEntityId,
        String displayName,
        String displayNameSi,
        String displayNameTa,
        String phone,
        String nicLast4,
        UUID registeredLocationId,
        String regionCode,
        String regionalMemberNo,
        String loyaltyTier,
        String status,
        boolean allLocationsPermitted,
        Set<UUID> permittedLocationIds,
        Map<String, String> regionalAttributes,
        Instant enrolledAt,
        Instant updatedAt) {

    public LoyaltyCustomerView {
        permittedLocationIds = permittedLocationIds == null ? Set.of() : Set.copyOf(permittedLocationIds);
        regionalAttributes = regionalAttributes == null ? Map.of() : Map.copyOf(regionalAttributes);
    }

    /**
     * Determines whether this customer is an active cooperative society member.
     * True if the regional member number is present or the tier is explicitly MEMBER.
     */
    public boolean isMember() {
        return (regionalMemberNo != null && !regionalMemberNo.isBlank()) || "MEMBER".equalsIgnoreCase(loyaltyTier);
    }
}
