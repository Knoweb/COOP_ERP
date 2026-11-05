package lk.coopfed.knoweb.m3pricing.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * Public SPI question published by M3 Pricing to determine customer-class eligibility
 * for targeted discount rules (PGL-M1-06).
 *
 * <p>Implemented by downstream modules (e.g. M7 Customers for MEMBER, M9 Integration for EMPLOYEE).
 * Pricing knows only this SPI and never imports downstream internal or query packages (layer rule ADR-05).
 */
public interface CustomerClassEligibilityProvider {

    /**
     * Determines whether this provider evaluates the given beneficiary class.
     */
    boolean supports(BeneficiaryClass beneficiaryClass);

    /**
     * Verifies that the given beneficiary credential is valid, active, and eligible at the location.
     *
     * @param identifier the explicit class-specific identifier (e.g. memberNo for MEMBER, employeeNo for EMPLOYEE)
     * @param locationId the shop location where pricing resolution occurs
     * @param scope the caller's tenant ScopeContext
     * @return true if the beneficiary is active and eligible at the location
     */
    boolean isEligible(String identifier, UUID locationId, ScopeContext scope);
}

