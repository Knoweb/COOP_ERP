package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.engine.Basket;
import lk.coopfed.knoweb.engine.BasketResult;
import lk.coopfed.knoweb.engine.LineInput;
import lk.coopfed.knoweb.engine.Quantity;
import lk.coopfed.knoweb.engine.TenderKind;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.ActivateRule;
import lk.coopfed.knoweb.m3pricing.api.AuthorRule;
import lk.coopfed.knoweb.m3pricing.api.BeneficiaryClass;
import lk.coopfed.knoweb.m3pricing.api.CreatePriceList;
import lk.coopfed.knoweb.m3pricing.api.PublishPriceList;
import lk.coopfed.knoweb.m3pricing.api.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.api.RulePredicate;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.internal.list.CreatePriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.PublishPriceListHandler;
import lk.coopfed.knoweb.m3pricing.internal.list.SetLinesHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.ActivateRuleHandler;
import lk.coopfed.knoweb.m3pricing.internal.rule.AuthorRuleHandler;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PGL-M1-06: Member & Employee Customer-Class Discount Rules PostgreSQL Integration Tests.
 * Tests end-to-end basket resolution with customer-class predicates, SPI inversion to m7customers and m9integration,
 * RLS isolation preventing cross-entity leakage, and coexistence of general, member, and employee rules.
 */
@Import(PinnedClock.class)
class CustomerClassDiscountPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID FEDERATION = TEST_FEDERATION;
    private static final UUID SOCIETY = UUID.fromString("0190e750-0000-7000-8000-000000000005");
    private static final UUID OTHER_SOCIETY = UUID.fromString("0190e750-0000-7000-8000-000000000006");
    private static final UUID SHOP = UUID.fromString("0190e750-0000-7000-8000-000000000105");
    private static final UUID SHOP_TWO = UUID.fromString("0190e750-0000-7000-8000-000000000107");
    private static final UUID OTHER_SHOP = UUID.fromString("0190e750-0000-7000-8000-000000000106");
    private static final UUID USER = UUID.fromString("0190e750-0000-7000-8000-000000000010");
    private static final UUID TAX_CATEGORY = UUID.fromString("0190e750-0000-7000-8000-000000000100");

    @Autowired
    AuthorRuleHandler author;

    @Autowired
    ActivateRuleHandler activate;

    @Autowired
    CreatePriceListHandler create;

    @Autowired
    SetLinesHandler setLines;

    @Autowired
    PublishPriceListHandler publish;

    @Autowired
    PricingQueries queries;

    private final UUID tea = Ids.next();
    private final UUID memberCustId = Ids.next();
    private final UUID otherMemberCustId = Ids.next();
    private final UUID empId = Ids.next();
    private final UUID otherEmpId = Ids.next();

    private final LocalDate today = PinnedClock.TODAY;
    private final LocalDate yesterday = today.minusDays(1);
    private final LocalDate tomorrow = today.plusDays(1);

    @BeforeEach
    void arrange() {
        clean();
        JdbcTemplate admin = superuserJdbc();

        // 1. Master data setup (UOM, Tax Category, Entities, Locations, SKUs)
        admin.update(
                "insert into catalogue.uom (uom_code, name_en, is_weight) values ('EA', 'Each', false) on conflict do nothing");
        admin.update(
                "insert into catalogue.tax_category (tax_category_id, code, name_en, owner_entity_id)"
                        + " values (?, 'M3TAX', 'M3 Tax Category', ?) on conflict do nothing",
                TAX_CATEGORY,
                FEDERATION);

        admin.update(
                """
                insert into party.entity (entity_id, entity_code, entity_type, legal_name_en, district,
                    financial_year_start_month, default_language, status)
                values (?, 'SOC-A', 'MPCS', 'Society A', 'Kurunegala', 1, 'si', 'ACTIVE'),
                       (?, 'SOC-B', 'MPCS', 'Society B', 'Kandy', 1, 'si', 'ACTIVE')
                on conflict (entity_id) do nothing
                """,
                SOCIETY,
                OTHER_SOCIETY);

        admin.update(
                """
                insert into party.location (location_id, owner_entity_id, location_code, location_type, name_en)
                values (?, ?, 'SHOP-A', 'SHOP', 'Shop A'),
                       (?, ?, 'SHOP-A2', 'SHOP', 'Shop A2'),
                       (?, ?, 'SHOP-B', 'SHOP', 'Shop B')
                on conflict (location_id) do nothing
                """,
                SHOP,
                SOCIETY,
                SHOP_TWO,
                SOCIETY,
                OTHER_SHOP,
                OTHER_SOCIETY);

        insertSku(admin, tea, "M3-TEA");

        // 2. Publish baseline retail price list (Tea @ 200.00)
        UUID listId = create.handle(new CreatePriceList("RETAIL", "Retail List"), own(SOCIETY));
        setLines.handle(
                new SetLines(listId, List.of(new SetLines.Line(tea, "EA", BigDecimal.ZERO, new BigDecimal("200.00")))),
                own(SOCIETY));
        publish.handle(new PublishPriceList(listId, today), own(SOCIETY));

        // 3. Register customers and loyalty member records (m7customers)
        admin.update(
                """
                insert into customers.customer (customer_id, display_name, language, status, registered_by_entity_id, owner_entity_id)
                values (?, 'Member Alpha', 'en', 'ACTIVE', ?, ?),
                       (?, 'Other Member Beta', 'en', 'ACTIVE', ?, ?)
                """,
                memberCustId,
                SOCIETY,
                SOCIETY,
                otherMemberCustId,
                OTHER_SOCIETY,
                OTHER_SOCIETY);

        admin.update(
                """
                insert into customers.loyalty_customer (loyalty_id, customer_id, owner_entity_id, registered_location_id,
                    region_code, regional_member_no, loyalty_tier, status, all_locations_permitted)
                values (?, ?, ?, ?, 'REG-A', 'MEM-101', 'MEMBER', 'ACTIVE', true),
                       (?, ?, ?, ?, 'REG-B', 'MEM-OTHER', 'MEMBER', 'ACTIVE', true)
                """,
                Ids.next(),
                memberCustId,
                SOCIETY,
                SHOP,
                Ids.next(),
                otherMemberCustId,
                OTHER_SOCIETY,
                OTHER_SHOP);

        // 4. Register HR employees (m9integration)
        admin.update(
                """
                insert into integration.hr_employee (employee_id, owner_entity_id, employee_no, full_name, nic, epf_no,
                    hourly_rate, location_id, status, created_at, updated_at)
                values (?, ?, 'EMP-201', 'Staff Alice', '199512345678', 'EPF-201', 0.00, null, 'ACTIVE', now(), now()),
                       (?, ?, 'EMP-OTHER', 'Staff Bob', '199612345678', 'EPF-202', 0.00, null, 'ACTIVE', now(), now())
                """,
                empId,
                SOCIETY,
                otherEmpId,
                OTHER_SOCIETY);

        kernel.reset();
    }

    @AfterEach
    void clean() {
        JdbcTemplate admin = superuserJdbc();
        admin.execute("truncate table pricing.discount_rule");
        admin.execute("truncate table pricing.price_list_line, pricing.price_list cascade");
        admin.execute("truncate table customers.loyalty_permitted_location, customers.loyalty_customer cascade");
        admin.execute("truncate table customers.customer cascade");
        admin.execute("truncate table integration.hr_employee cascade");
        admin.execute("truncate table catalogue.sku cascade");
        admin.update("delete from catalogue.tax_category where tax_category_id = ?", TAX_CATEGORY);
    }

    @Test
    void generalBasketWithoutBeneficiaryResolvesShelfPrice() {
        Basket basket = new Basket(List.of(new LineInput(tea, "EA", Quantity.of("1.000"), null, null)));
        BasketResult result = queries.resolveBasket(SHOP, tomorrow, TenderKind.CASH, basket, null, null, own(SOCIETY))
                .orElseThrow();

        assertThat(result.getTotal().getAmount()).isEqualByComparingTo("200.00");
        assertThat(result.getLines()).hasSize(1);
        assertThat(result.getLines().get(0).getUnitPrice().getAmount()).isEqualByComparingTo("200.00");
        assertThat(result.getLines().get(0).getLineTotal().getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void memberDiscountAppliesOnlyToEligibleMember() {
        // Author and activate 10% discount for MEMBER customer class
        UUID memberRuleId = author.handle(
                new AuthorRule(
                        "Member 10% Off",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(tea, null, null, null, null, "MEMBER"),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00")),
                        100,
                        today,
                        tomorrow.plusDays(10)),
                own(SOCIETY));
        activate.handle(new ActivateRule(memberRuleId), own(SOCIETY));

        Basket basket = new Basket(List.of(new LineInput(tea, "EA", Quantity.of("1.000"), null, null)));

        // 1. Shopper without member credentials does not get member discount
        BasketResult generalShopper = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, null, null, own(SOCIETY))
                .orElseThrow();
        assertThat(generalShopper.getTotal().getAmount()).isEqualByComparingTo("200.00");

        // 2. Shopper with valid member credentials gets 10% discount: 200 - 20 = 180.00
        BasketResult memberShopper = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.MEMBER, "MEM-101", own(SOCIETY))
                .orElseThrow();
        assertThat(memberShopper.getTotal().getAmount()).isEqualByComparingTo("180.00");
        assertThat(memberShopper.getLines().get(0).getLineTotal().getAmount()).isEqualByComparingTo("180.00");
        assertThat(memberShopper.getLines().get(0).getRuleId()).isEqualTo(memberRuleId);

        // 3. Shopper with invalid member credentials does not get discount
        BasketResult invalidShopper = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.MEMBER, "MEM-404", own(SOCIETY))
                .orElseThrow();
        assertThat(invalidShopper.getTotal().getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void employeeDiscountAppliesOnlyToEligibleEmployee() {
        // Author and activate 15% discount for EMPLOYEE customer class
        UUID employeeRuleId = author.handle(
                new AuthorRule(
                        "Employee 15% Off",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(tea, null, null, null, null, "EMPLOYEE"),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("15.00")),
                        100,
                        today,
                        tomorrow.plusDays(10)),
                own(SOCIETY));
        activate.handle(new ActivateRule(employeeRuleId), own(SOCIETY));

        Basket basket = new Basket(List.of(new LineInput(tea, "EA", Quantity.of("1.000"), null, null)));

        // 1. Shopper without employee credentials gets regular price
        BasketResult generalShopper = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, null, null, own(SOCIETY))
                .orElseThrow();
        assertThat(generalShopper.getTotal().getAmount()).isEqualByComparingTo("200.00");

        // 2. Shopper with valid employee credentials gets 15% discount: 200 - 30 = 170.00
        BasketResult employeeShopper = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.EMPLOYEE, "EMP-201", own(SOCIETY))
                .orElseThrow();
        assertThat(employeeShopper.getTotal().getAmount()).isEqualByComparingTo("170.00");
        assertThat(employeeShopper.getLines().get(0).getLineTotal().getAmount()).isEqualByComparingTo("170.00");
        assertThat(employeeShopper.getLines().get(0).getRuleId()).isEqualTo(employeeRuleId);

        // 3. Shopper with unknown employee number gets regular price
        BasketResult unknownShopper = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.EMPLOYEE, "EMP-404", own(SOCIETY))
                .orElseThrow();
        assertThat(unknownShopper.getTotal().getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void rlsIsolationPreventsCrossEntityMemberOrEmployeeEligibility() {
        // Author and activate MEMBER 10% off and EMPLOYEE 15% off at Society A
        UUID memberRuleId = author.handle(
                new AuthorRule(
                        "Member 10% Off",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(tea, null, null, null, null, "MEMBER"),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00")),
                        100,
                        today,
                        tomorrow.plusDays(10)),
                own(SOCIETY));
        activate.handle(new ActivateRule(memberRuleId), own(SOCIETY));

        UUID employeeRuleId = author.handle(
                new AuthorRule(
                        "Employee 15% Off",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(tea, null, null, null, null, "EMPLOYEE"),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("15.00")),
                        100,
                        today,
                        tomorrow.plusDays(10)),
                own(SOCIETY));
        activate.handle(new ActivateRule(employeeRuleId), own(SOCIETY));

        Basket basket = new Basket(List.of(new LineInput(tea, "EA", Quantity.of("1.000"), null, null)));

        // Society B's member MEM-OTHER presented at Society A's shop under Society A's scope -> NOT eligible under RLS
        BasketResult crossMember = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.MEMBER, "MEM-OTHER", own(SOCIETY))
                .orElseThrow();
        assertThat(crossMember.getTotal().getAmount()).isEqualByComparingTo("200.00");

        // Society B's employee EMP-OTHER presented at Society A's shop under Society A's scope -> NOT eligible under
        // RLS
        BasketResult crossEmployee = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.EMPLOYEE, "EMP-OTHER", own(SOCIETY))
                .orElseThrow();
        assertThat(crossEmployee.getTotal().getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void bothMemberAndEmployeeRulesCanCoexistAtDifferentBeneficiaryEvaluations() {
        UUID memberRuleId = author.handle(
                new AuthorRule(
                        "Member 10% Off",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(tea, null, null, null, null, "MEMBER"),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00")),
                        100,
                        today,
                        tomorrow.plusDays(10)),
                own(SOCIETY));
        activate.handle(new ActivateRule(memberRuleId), own(SOCIETY));

        UUID employeeRuleId = author.handle(
                new AuthorRule(
                        "Employee 15% Off",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(tea, null, null, null, null, "EMPLOYEE"),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("15.00")),
                        100,
                        today,
                        tomorrow.plusDays(10)),
                own(SOCIETY));
        activate.handle(new ActivateRule(employeeRuleId), own(SOCIETY));

        Basket basket = new Basket(List.of(new LineInput(tea, "EA", Quantity.of("1.000"), null, null)));

        // Member evaluation
        BasketResult memberRes = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.MEMBER, "MEM-101", own(SOCIETY))
                .orElseThrow();
        assertThat(memberRes.getTotal().getAmount()).isEqualByComparingTo("180.00");

        // Employee evaluation
        BasketResult employeeRes = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.EMPLOYEE, "EMP-201", own(SOCIETY))
                .orElseThrow();
        assertThat(employeeRes.getTotal().getAmount()).isEqualByComparingTo("170.00");

        // General shopper evaluation
        BasketResult generalRes = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, null, null, own(SOCIETY))
                .orElseThrow();
        assertThat(generalRes.getTotal().getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void guardRefusesIncompleteBeneficiarySpecification() {
        Basket basket = new Basket(List.of(new LineInput(tea, "EA", Quantity.of("1.000"), null, null)));

        assertThatThrownBy(() -> queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.MEMBER, null, own(SOCIETY)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo("m3.basket.beneficiary_incomplete"));

        assertThatThrownBy(() ->
                        queries.resolveBasket(SHOP, tomorrow, TenderKind.CASH, basket, null, "MEM-101", own(SOCIETY)))
                .isInstanceOf(ProblemException.class)
                .satisfies(e ->
                        assertThat(((ProblemException) e).messageId()).isEqualTo("m3.basket.beneficiary_incomplete"));
    }

    @Test
    void branchScopedDiscountAppliesOnlyAtTargetShop() {
        UUID branchRule = author.handle(
                new AuthorRule(
                        "Shop A Special",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(tea, null, null, null, null, null, SHOP),
                        new RuleBenefit("FIXED_PRICE", new BigDecimal("150.00")),
                        10,
                        today,
                        null),
                own(SOCIETY));
        activate.handle(new ActivateRule(branchRule), own(SOCIETY));

        Basket basket = new Basket(List.of(new LineInput(tea, "EA", Quantity.of("1.000"), null, null)));

        BasketResult shopResult = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, null, null, own(SOCIETY))
                .orElseThrow();
        assertThat(shopResult.getTotal().getAmount()).isEqualByComparingTo("150.00");

        BasketResult otherShopResult = queries.resolveBasket(
                        SHOP_TWO, tomorrow, TenderKind.CASH, basket, null, null, own(SOCIETY))
                .orElseThrow();
        assertThat(otherShopResult.getTotal().getAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void scannedMemberBarcodeWithWhitespaceOrCaseResolvesMemberDiscount() {
        UUID memberRule = author.handle(
                new AuthorRule(
                        "Member Discount",
                        "TIME_LIMITED_PRICE",
                        new RulePredicate(tea, null, null, null, null, "MEMBER"),
                        new RuleBenefit("PERCENT_OFF", new BigDecimal("10.00")),
                        10,
                        today,
                        null),
                own(SOCIETY));
        activate.handle(new ActivateRule(memberRule), own(SOCIETY));

        Basket basket = new Basket(List.of(new LineInput(tea, "EA", Quantity.of("1.000"), null, null)));

        BasketResult barcodeResult = queries.resolveBasket(
                        SHOP, tomorrow, TenderKind.CASH, basket, BeneficiaryClass.MEMBER, "  mem-101  ", own(SOCIETY))
                .orElseThrow();
        assertThat(barcodeResult.getTotal().getAmount()).isEqualByComparingTo("180.00");
    }

    private static void insertSku(JdbcTemplate admin, UUID skuId, String code) {
        admin.update(
                "insert into catalogue.sku (sku_id, sku_code, owner_entity_id, status, short_name_en, short_name_si,"
                        + " short_name_ta, base_uom_code, sold_by_weight, has_printed_mrp, tax_category_id)"
                        + " values (?, ?, ?, 'SHARED', ?, ?, ?, 'EA', false, true, ?)",
                skuId,
                code,
                FEDERATION,
                code,
                code,
                code,
                TAX_CATEGORY);
    }

    private static ScopeContext own(UUID entity) {
        return new ScopeContext(
                USER,
                null,
                entity,
                List.of(new Scope(entity, null)),
                new Scope(entity, null),
                PolicyClass.OWN,
                Set.of(),
                Instant.now(),
                Locale.ENGLISH,
                null);
    }
}
