package lk.coopfed.knoweb.m7customers.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Command to register or enroll a customer in centralized loyalty with regional registration (PGL-M3-01).
 *
 * @param customerId             null to register a new customer; non-null to enroll an existing active customer
 * @param displayName            name for new customer
 * @param displayNameSi          Sinhala name (optional)
 * @param displayNameTa          Tamil name (optional)
 * @param language               en, si, or ta (default si)
 * @param phone                  primary mobile phone
 * @param nic                    National Identity Card (optional)
 * @param registeredLocationId   home branch/shop where regionally registered
 * @param regionCode             regional registration code (e.g. POL-01, CP-KANDY)
 * @param regionalMemberNo       optional regional membership book/card number
 * @param loyaltyTier            loyalty tier (STANDARD, SILVER, GOLD, PLATINUM, MEMBER; default STANDARD)
 * @param allLocationsPermitted  whether loyalty identity is accepted across all branches of the society
 * @param permittedLocationIds   explicit set of permitted branch location IDs when not all allowed
 * @param regionalAttributes     custom regional attributes (e.g. cluster, zone, agricultural division)
 */
public record RegisterLoyaltyCustomer(
        UUID customerId,
        String displayName,
        String displayNameSi,
        String displayNameTa,
        String language,
        String phone,
        String nic,
        UUID registeredLocationId,
        String regionCode,
        String regionalMemberNo,
        String loyaltyTier,
        boolean allLocationsPermitted,
        Set<UUID> permittedLocationIds,
        Map<String, String> regionalAttributes) {

    public RegisterLoyaltyCustomer {
        permittedLocationIds = permittedLocationIds == null ? Set.of() : Set.copyOf(permittedLocationIds);
        regionalAttributes = regionalAttributes == null ? Map.of() : Map.copyOf(regionalAttributes);
        loyaltyTier = loyaltyTier == null || loyaltyTier.isBlank()
                ? "STANDARD"
                : loyaltyTier.strip().toUpperCase();
    }
}
