package lk.coopfed.knoweb.m7customers;

import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.SHOP_2;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.office;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.other;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.till;
import static lk.coopfed.knoweb.m7customers.CustomersFixture.till2;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.m7customers.api.LoyaltyCustomerEnrolled;
import lk.coopfed.knoweb.m7customers.api.RegisterLoyaltyCustomer;
import lk.coopfed.knoweb.m7customers.api.UpdateRegionalRegistration;
import lk.coopfed.knoweb.m7customers.internal.loyalty.RegisterLoyaltyCustomerHandler;
import lk.coopfed.knoweb.m7customers.internal.loyalty.UpdateRegionalRegistrationHandler;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerQueries;
import lk.coopfed.knoweb.m7customers.query.LoyaltyCustomerView;
import lk.coopfed.knoweb.m7customers.query.LoyaltyEligibility;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests proving centralized loyalty identity across permitted branches,
 * regional registration maintenance, scope/owner isolation, and access control (PGL-M3-01).
 */
class LoyaltyCustomerPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final String PHONE = "0700000888";
    private static final String NIC = "199000005678";
    private static final String OLD_NIC_FORM = "900005678V";

    @Autowired
    Handles<RegisterLoyaltyCustomer, UUID> registerLoyalty;

    @Autowired
    Handles<UpdateRegionalRegistration, Void> updateRegional;

    @Autowired
    LoyaltyCustomerQueries loyaltyQueries;

    @BeforeEach
    void setup() {
        CustomersFixture.arrange(superuserJdbc());
        kernel.reset();
    }

    @AfterEach
    void clean() {
        CustomersFixture.clean(superuserJdbc());
    }

    @Test
    void loyaltyCustomerUniquelyResolvedAcrossAllowedBranches() {
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                null,
                "Perera Silva",
                "පෙරේරා සිල්වා",
                "பெரேரா சில்வா",
                "si",
                PHONE,
                NIC,
                SHOP,
                "POL-ZONE-01",
                "MEM-99901",
                "GOLD",
                true,
                Set.of(),
                Map.of("cluster", "Agri-North", "division", "Polgolla"));

        UUID loyaltyId = registerLoyalty.handle(cmd, office());
        assertThat(loyaltyId).isNotNull();

        // 1. Resolve from Branch 1 (SHOP) by phone
        Optional<LoyaltyCustomerView> viewFromShop1 = loyaltyQueries.lookupByPhone(PHONE, till());
        assertThat(viewFromShop1).isPresent();
        LoyaltyCustomerView customer = viewFromShop1.get();
        assertThat(customer.loyaltyId()).isEqualTo(loyaltyId);
        assertThat(customer.registeredLocationId()).isEqualTo(SHOP);
        assertThat(customer.regionCode()).isEqualTo("POL-ZONE-01");
        assertThat(customer.regionalMemberNo()).isEqualTo("MEM-99901");
        assertThat(customer.loyaltyTier()).isEqualTo("GOLD");
        assertThat(customer.status()).isEqualTo("ACTIVE");
        assertThat(customer.nicLast4()).isEqualTo("5678");
        assertThat(customer.regionalAttributes()).containsEntry("cluster", "Agri-North");

        // 2. Resolve from Branch 2 (SHOP_2) - Centralized Loyalty Identity across branches
        Optional<LoyaltyCustomerView> viewFromShop2 = loyaltyQueries.lookupByPhone(PHONE, till2());
        assertThat(viewFromShop2).isPresent();
        assertThat(viewFromShop2.get().customerId()).isEqualTo(customer.customerId());

        // 3. Resolve by NIC (using old format equivalent) across allowed branches
        Optional<LoyaltyCustomerView> viewByNic = loyaltyQueries.lookupByNic(OLD_NIC_FORM, till2());
        assertThat(viewByNic).isPresent();
        assertThat(viewByNic.get().customerId()).isEqualTo(customer.customerId());

        // 4. Check eligibility across allowed branches
        LoyaltyEligibility el1 = loyaltyQueries.checkEligibility(customer.customerId(), SHOP, till());
        assertThat(el1.eligible()).isTrue();
        assertThat(el1.reason()).isEqualTo("ELIGIBLE");
        assertThat(el1.loyaltyTier()).isEqualTo("GOLD");

        LoyaltyEligibility el2 = loyaltyQueries.checkEligibility(customer.customerId(), SHOP_2, till2());
        assertThat(el2.eligible()).isTrue();
        assertThat(el2.reason()).isEqualTo("ELIGIBLE");

        // 5. Query by region code
        var regionList = loyaltyQueries.listByRegion("POL-ZONE-01", office());
        assertThat(regionList).hasSize(1);
        assertThat(regionList.get(0).customerId()).isEqualTo(customer.customerId());

        // 6. Audit & events have NO PII
        var audits = kernel.committedAudit();
        assertThat(audits).anySatisfy(a -> {
            assertThat(a.eventType()).isEqualTo(RegisterLoyaltyCustomerHandler.AUDIT_LOYALTY_ENROLLED);
            assertThat(a.after()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) a.after();
            assertThat(map).containsEntry("regionCode", "POL-ZONE-01");
            assertThat(map).containsEntry("loyaltyTier", "GOLD");
            assertThat(map.keySet()).doesNotContain("phone", "nic", "displayName", "name");
        });

        var events = kernel.committedEvents();
        assertThat(events).anySatisfy(e -> {
            assertThat(e).isInstanceOf(LoyaltyCustomerEnrolled.class);
            LoyaltyCustomerEnrolled payload = (LoyaltyCustomerEnrolled) e;
            assertThat(payload.loyaltyId()).isEqualTo(loyaltyId);
            assertThat(payload.regionCode()).isEqualTo("POL-ZONE-01");
        });
    }

    @Test
    void branchRestrictedLoyaltyCustomerAccessControlled() {
        // Enrolled specifically restricted to SHOP only (not all locations permitted)
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                null,
                "Kamal Gunaratne",
                null,
                null,
                "si",
                "0700000777",
                null,
                SHOP,
                "POL-ZONE-02",
                "MEM-88802",
                "STANDARD",
                false,
                Set.of(SHOP),
                Map.of());

        UUID loyaltyId = registerLoyalty.handle(cmd, office());

        // Permitted branch (SHOP) can read and is eligible
        Optional<LoyaltyCustomerView> viewFromShop = loyaltyQueries.lookupByPhone("0700000777", till());
        assertThat(viewFromShop).isPresent();

        LoyaltyEligibility elShop =
                loyaltyQueries.checkEligibility(viewFromShop.get().customerId(), SHOP, till());
        assertThat(elShop.eligible()).isTrue();

        // Unpermitted branch (SHOP_2):
        // Evaluating eligibility for unpermitted branch reports LOCATION_NOT_PERMITTED
        LoyaltyEligibility elShop2 =
                loyaltyQueries.checkEligibility(viewFromShop.get().customerId(), SHOP_2, office());
        assertThat(elShop2.eligible()).isFalse();
        assertThat(elShop2.reason()).isEqualTo("LOCATION_NOT_PERMITTED");

        // Direct branch till query from unpermitted branch (SHOP_2) is blocked by RLS
        Optional<LoyaltyCustomerView> viewFromShop2 = loyaltyQueries.lookupByPhone("0700000777", till2());
        assertThat(viewFromShop2).isEmpty();
    }

    @Test
    void ownerEntityIsolationPreserved() {
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                null,
                "Saman Kumara",
                null,
                null,
                "si",
                "0700000666",
                "199511112222",
                SHOP,
                "POL-ZONE-03",
                "MEM-77703",
                "SILVER",
                true,
                Set.of(),
                Map.of());

        registerLoyalty.handle(cmd, office());

        // Another society (OTHER) queries by phone or NIC: returns empty (RLS isolation)
        assertThat(loyaltyQueries.lookupByPhone("0700000666", other())).isEmpty();
        assertThat(loyaltyQueries.lookupByNic("199511112222", other())).isEmpty();
    }

    @Test
    void updateRegionalRegistrationMaintainsAttributesAndPermissions() {
        RegisterLoyaltyCustomer cmd = new RegisterLoyaltyCustomer(
                null,
                "Anura Bandara",
                null,
                null,
                "si",
                "0700000555",
                null,
                SHOP,
                "POL-ZONE-01",
                "OLD-REG-01",
                "STANDARD",
                false,
                Set.of(SHOP),
                Map.of("zone", "OldZone"));

        UUID loyaltyId = registerLoyalty.handle(cmd, office());
        LoyaltyCustomerView initial =
                loyaltyQueries.lookupByPhone("0700000555", office()).orElseThrow();

        // Update regional registration to new branch, tier PLATINUM, and add SHOP_2 to permitted locations
        UpdateRegionalRegistration updateCmd = new UpdateRegionalRegistration(
                initial.customerId(),
                SHOP_2,
                "POL-ZONE-02",
                "NEW-REG-02",
                "PLATINUM",
                "ACTIVE",
                false,
                Set.of(SHOP, SHOP_2),
                Map.of("zone", "NewZone", "cluster", "Agri-South"));

        updateRegional.handle(updateCmd, office());

        // Verify updated view
        LoyaltyCustomerView updated = loyaltyQueries
                .getLoyaltyCustomer(initial.customerId(), office())
                .orElseThrow();
        assertThat(updated.registeredLocationId()).isEqualTo(SHOP_2);
        assertThat(updated.regionCode()).isEqualTo("POL-ZONE-02");
        assertThat(updated.regionalMemberNo()).isEqualTo("NEW-REG-02");
        assertThat(updated.loyaltyTier()).isEqualTo("PLATINUM");
        assertThat(updated.permittedLocationIds()).containsExactlyInAnyOrder(SHOP, SHOP_2);
        assertThat(updated.regionalAttributes()).containsEntry("zone", "NewZone");

        // Verify now eligible at both SHOP and SHOP_2
        assertThat(loyaltyQueries
                        .checkEligibility(initial.customerId(), SHOP, till())
                        .eligible())
                .isTrue();
        assertThat(loyaltyQueries
                        .checkEligibility(initial.customerId(), SHOP_2, till2())
                        .eligible())
                .isTrue();

        // Audit verify
        var audits = kernel.committedAudit();
        assertThat(audits).anySatisfy(a -> {
            assertThat(a.eventType()).isEqualTo(UpdateRegionalRegistrationHandler.AUDIT_REGIONAL_UPDATED);
            assertThat(a.after()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) a.after();
            assertThat(map).containsEntry("regionCode", "POL-ZONE-02");
            assertThat(map).containsEntry("loyaltyTier", "PLATINUM");
            assertThat(map.keySet()).doesNotContain("phone", "nic", "displayName", "name");
        });
    }
}
