package lk.coopfed.knoweb.m7customers.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Command to update regional registration details and permitted branches for a loyalty customer (PGL-M3-01).
 */
public record UpdateRegionalRegistration(
        UUID customerId,
        UUID registeredLocationId,
        String regionCode,
        String regionalMemberNo,
        String loyaltyTier,
        String status,
        boolean allLocationsPermitted,
        Set<UUID> permittedLocationIds,
        Map<String, String> regionalAttributes) {

    public UpdateRegionalRegistration {
        permittedLocationIds = permittedLocationIds == null ? Set.of() : Set.copyOf(permittedLocationIds);
        regionalAttributes = regionalAttributes == null ? Map.of() : Map.copyOf(regionalAttributes);
        loyaltyTier = loyaltyTier == null || loyaltyTier.isBlank()
                ? "STANDARD"
                : loyaltyTier.strip().toUpperCase();
        status = status == null || status.isBlank() ? "ACTIVE" : status.strip().toUpperCase();
    }
}
