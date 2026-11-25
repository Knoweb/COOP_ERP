package lk.coopfed.knoweb.m9integration.internal.integration;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.BeneficiaryClass;
import lk.coopfed.knoweb.m3pricing.api.CustomerClassEligibilityProvider;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries.EmployeeView;
import org.springframework.stereotype.Component;

/**
 * Authoritative employee eligibility provider for pricing discount rules (PGL-M1-06).
 */
@Component
class EmployeeEligibilityForPricing implements CustomerClassEligibilityProvider {

    private final EmployeeQueries employeeQueries;

    EmployeeEligibilityForPricing(EmployeeQueries employeeQueries) {
        this.employeeQueries = Objects.requireNonNull(employeeQueries, "employeeQueries");
    }

    @Override
    public boolean supports(BeneficiaryClass beneficiaryClass) {
        return BeneficiaryClass.EMPLOYEE.equals(beneficiaryClass);
    }

    @Override
    public boolean isEligible(String identifier, UUID locationId, ScopeContext scope) {
        if (identifier == null || identifier.isBlank() || scope == null) {
            return false;
        }

        Optional<EmployeeView> employeeOpt = employeeQueries.lookupByEmployeeNo(identifier.trim(), scope);
        if (employeeOpt.isEmpty()) {
            return false;
        }

        EmployeeView employee = employeeOpt.get();

        // 1. Must be active
        if (!"ACTIVE".equalsIgnoreCase(employee.status())) {
            return false;
        }

        // 2. Owner entity must match scope entity
        if (scope.entityId() != null && !scope.entityId().equals(employee.ownerEntityId())) {
            return false;
        }

        // 3. Location restriction if the employee profile is assigned to a specific location
        if (employee.locationId() != null
                && locationId != null
                && !employee.locationId().equals(locationId)) {
            return false;
        }

        return true;
    }
}
