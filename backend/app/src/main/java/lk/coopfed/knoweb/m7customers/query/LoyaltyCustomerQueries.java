package lk.coopfed.knoweb.m7customers.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Stable query seam for centralized loyalty customer identity and eligibility (PGL-M3-01).
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
}
