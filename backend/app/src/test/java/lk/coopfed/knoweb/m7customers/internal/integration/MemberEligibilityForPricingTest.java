package lk.coopfed.knoweb.m7customers.internal.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.BeneficiaryClass;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerQueries;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemberEligibilityForPricingTest {

    private LoyaltyCustomerQueries loyaltyQueries;
    private MemberEligibilityForPricing provider;
    private ScopeContext scope;
    private final UUID locationA = UUID.randomUUID();
    private final UUID locationB = UUID.randomUUID();
    private final UUID locationC = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        loyaltyQueries = mock(LoyaltyCustomerQueries.class);
        provider = new MemberEligibilityForPricing(loyaltyQueries);
        scope = ScopeContext.dev(UUID.randomUUID(), UUID.randomUUID(), null);
    }

    @Test
    void supportsOnlyMemberBeneficiaryClass() {
        assertThat(provider.supports(BeneficiaryClass.MEMBER)).isTrue();
        assertThat(provider.supports(BeneficiaryClass.EMPLOYEE)).isFalse();
        assertThat(provider.supports(null)).isFalse();
    }

    @Test
    void returnsFalseForEmptyOrNullIdentifierOrScope() {
        assertThat(provider.isEligible(null, locationA, scope)).isFalse();
        assertThat(provider.isEligible("", locationA, scope)).isFalse();
        assertThat(provider.isEligible("  ", locationA, scope)).isFalse();
        assertThat(provider.isEligible("MEM-001", locationA, null)).isFalse();
    }

    @Test
    void returnsFalseWhenMemberNotFound() {
        when(loyaltyQueries.lookupByMemberNo(eq("MEM-404"), any())).thenReturn(Optional.empty());
        assertThat(provider.isEligible("MEM-404", locationA, scope)).isFalse();
    }

    @Test
    void returnsFalseWhenMemberInactive() {
        LoyaltyCustomerView view = new LoyaltyCustomerView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                scope.entityId(),
                "Inactive Member",
                null,
                null,
                "0771234567",
                "1234",
                locationA,
                "REG-1",
                "MEM-001",
                "MEMBER",
                "INACTIVE",
                true,
                Set.of(),
                Map.of(),
                Instant.now(),
                Instant.now());
        when(loyaltyQueries.lookupByMemberNo(eq("MEM-001"), any())).thenReturn(Optional.of(view));

        assertThat(provider.isEligible("MEM-001", locationA, scope)).isFalse();
    }

    @Test
    void returnsFalseWhenCustomerIsNotMember() {
        LoyaltyCustomerView view = new LoyaltyCustomerView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                scope.entityId(),
                "Regular Customer",
                null,
                null,
                "0771234567",
                "1234",
                locationA,
                "REG-1",
                null,
                "REGULAR",
                "ACTIVE",
                true,
                Set.of(),
                Map.of(),
                Instant.now(),
                Instant.now());
        when(loyaltyQueries.lookupByMemberNo(eq("REG-001"), any())).thenReturn(Optional.of(view));

        assertThat(provider.isEligible("REG-001", locationA, scope)).isFalse();
    }

    @Test
    void returnsTrueWhenActiveMemberWithAllLocationsPermitted() {
        LoyaltyCustomerView view = new LoyaltyCustomerView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                scope.entityId(),
                "Active Member",
                null,
                null,
                "0771234567",
                "1234",
                locationA,
                "REG-1",
                "MEM-001",
                "MEMBER",
                "ACTIVE",
                true,
                Set.of(),
                Map.of(),
                Instant.now(),
                Instant.now());
        when(loyaltyQueries.lookupByMemberNo(eq("MEM-001"), any())).thenReturn(Optional.of(view));

        assertThat(provider.isEligible("MEM-001", locationC, scope)).isTrue();
    }

    @Test
    void handlesLocationRestrictionsCorrectly() {
        LoyaltyCustomerView view = new LoyaltyCustomerView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                scope.entityId(),
                "Restricted Member",
                null,
                null,
                "0771234567",
                "1234",
                locationA,
                "REG-1",
                "MEM-001",
                "MEMBER",
                "ACTIVE",
                false,
                Set.of(locationB),
                Map.of(),
                Instant.now(),
                Instant.now());
        when(loyaltyQueries.lookupByMemberNo(eq("MEM-001"), any())).thenReturn(Optional.of(view));

        // Permitted at registered location A
        assertThat(provider.isEligible("MEM-001", locationA, scope)).isTrue();
        // Permitted at location B in permittedLocationIds
        assertThat(provider.isEligible("MEM-001", locationB, scope)).isTrue();
        // Refused at unlisted location C
        assertThat(provider.isEligible("MEM-001", locationC, scope)).isFalse();
    }
}
