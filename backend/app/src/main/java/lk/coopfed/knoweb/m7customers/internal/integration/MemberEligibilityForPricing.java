package lk.coopfed.knoweb.m7customers.internal.integration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.BeneficiaryClass;
import lk.coopfed.knoweb.m3pricing.api.CustomerClassEligibilityProvider;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerQueries;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerView;
import org.springframework.stereotype.Component;

/**
 * Authoritative member eligibility provider for pricing discount rules (PGL-M1-06).
 * Evaluates membership strictly using regional member number without fallback to phone/NIC cascades.
 */
@Component
class MemberEligibilityForPricing implements CustomerClassEligibilityProvider {

    private final LoyaltyCustomerQueries loyaltyQueries;

    MemberEligibilityForPricing(LoyaltyCustomerQueries loyaltyQueries) {
        this.loyaltyQueries = Objects.requireNonNull(loyaltyQueries, "loyaltyQueries");
    }

    @Override
    public boolean supports(BeneficiaryClass beneficiaryClass) {
        return BeneficiaryClass.MEMBER.equals(beneficiaryClass);
    }

    @Override
    public boolean isEligible(String identifier, UUID locationId, ScopeContext scope) {
        if (identifier == null || identifier.isBlank() || scope == null) {
            return false;
        }

        Optional<LoyaltyCustomerView> customerOpt = loyaltyQueries.lookupByMemberNo(identifier.trim(), scope);
        if (customerOpt.isEmpty()) {
            return false;
        }

        LoyaltyCustomerView customer = customerOpt.get();

        // 1. Must be an active record
        if (!"ACTIVE".equalsIgnoreCase(customer.status())) {
            return false;
        }

        // 2. Must qualify as a society member
        if (!customer.isMember()) {
            return false;
        }

        // 3. Location restriction if customer is not permitted at all locations
        if (locationId != null && !customer.allLocationsPermitted()) {
            if (!locationId.equals(customer.registeredLocationId())
                    && (customer.permittedLocationIds() == null
                            || !customer.permittedLocationIds().contains(locationId))) {
                return false;
            }
        }

        return true;
    }
}
