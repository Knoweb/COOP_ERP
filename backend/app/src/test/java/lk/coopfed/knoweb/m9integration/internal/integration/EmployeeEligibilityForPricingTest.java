package lk.coopfed.knoweb.m9integration.internal.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.BeneficiaryClass;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries;
import lk.coopfed.knoweb.m9integration.query.EmployeeQueries.EmployeeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmployeeEligibilityForPricingTest {

    private EmployeeQueries employeeQueries;
    private EmployeeEligibilityForPricing provider;
    private ScopeContext scope;
    private final UUID entityId = UUID.randomUUID();
    private final UUID locationA = UUID.randomUUID();
    private final UUID locationB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        employeeQueries = mock(EmployeeQueries.class);
        provider = new EmployeeEligibilityForPricing(employeeQueries);
        scope = ScopeContext.dev(UUID.randomUUID(), entityId, null);
    }

    @Test
    void supportsOnlyEmployeeBeneficiaryClass() {
        assertThat(provider.supports(BeneficiaryClass.EMPLOYEE)).isTrue();
        assertThat(provider.supports(BeneficiaryClass.MEMBER)).isFalse();
        assertThat(provider.supports(null)).isFalse();
    }

    @Test
    void returnsFalseForEmptyOrNullIdentifierOrScope() {
        assertThat(provider.isEligible(null, locationA, scope)).isFalse();
        assertThat(provider.isEligible("", locationA, scope)).isFalse();
        assertThat(provider.isEligible("  ", locationA, scope)).isFalse();
        assertThat(provider.isEligible("EMP-001", locationA, null)).isFalse();
    }

    @Test
    void returnsFalseWhenEmployeeNotFound() {
        when(employeeQueries.lookupByEmployeeNo(eq("EMP-404"), any())).thenReturn(Optional.empty());
        assertThat(provider.isEligible("EMP-404", locationA, scope)).isFalse();
    }

    @Test
    void returnsFalseWhenEmployeeInactive() {
        EmployeeView view = new EmployeeView(
                UUID.randomUUID(),
                entityId,
                "EMP-001",
                "Perera Silva",
                "199012345678",
                "EPF-123",
                BigDecimal.ZERO,
                locationA,
                "INACTIVE",
                Instant.now(),
                Instant.now());
        when(employeeQueries.lookupByEmployeeNo(eq("EMP-001"), any())).thenReturn(Optional.of(view));

        assertThat(provider.isEligible("EMP-001", locationA, scope)).isFalse();
    }

    @Test
    void returnsFalseWhenEntityDoesNotMatchScope() {
        UUID otherEntityId = UUID.randomUUID();
        EmployeeView view = new EmployeeView(
                UUID.randomUUID(),
                otherEntityId,
                "EMP-001",
                "Perera Silva",
                "199012345678",
                "EPF-123",
                BigDecimal.ZERO,
                locationA,
                "ACTIVE",
                Instant.now(),
                Instant.now());
        when(employeeQueries.lookupByEmployeeNo(eq("EMP-001"), any())).thenReturn(Optional.of(view));

        assertThat(provider.isEligible("EMP-001", locationA, scope)).isFalse();
    }

    @Test
    void returnsTrueWhenActiveAndLocationMatchesOrNotRestricted() {
        // Location not restricted (null locationId on employee)
        EmployeeView unrestricted = new EmployeeView(
                UUID.randomUUID(),
                entityId,
                "EMP-ALL",
                "Unrestricted Employee",
                "199012345678",
                "EPF-123",
                BigDecimal.ZERO,
                null,
                "ACTIVE",
                Instant.now(),
                Instant.now());
        when(employeeQueries.lookupByEmployeeNo(eq("EMP-ALL"), any())).thenReturn(Optional.of(unrestricted));

        assertThat(provider.isEligible("EMP-ALL", locationA, scope)).isTrue();
        assertThat(provider.isEligible("EMP-ALL", locationB, scope)).isTrue();

        // Location restricted to locationA
        EmployeeView restricted = new EmployeeView(
                UUID.randomUUID(),
                entityId,
                "EMP-LOCA",
                "Shop A Employee",
                "199012345679",
                "EPF-124",
                BigDecimal.ZERO,
                locationA,
                "ACTIVE",
                Instant.now(),
                Instant.now());
        when(employeeQueries.lookupByEmployeeNo(eq("EMP-LOCA"), any())).thenReturn(Optional.of(restricted));

        assertThat(provider.isEligible("EMP-LOCA", locationA, scope)).isTrue();
        assertThat(provider.isEligible("EMP-LOCA", locationB, scope)).isFalse();
    }
}
