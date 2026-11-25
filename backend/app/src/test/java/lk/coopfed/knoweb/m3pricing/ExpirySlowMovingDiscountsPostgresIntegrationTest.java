package lk.coopfed.knoweb.m3pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m3pricing.api.EvaluateExpirySlowMovingDiscounts;
import lk.coopfed.knoweb.m3pricing.api.ExpirySlowMovingDiscountsEvaluated;
import lk.coopfed.knoweb.m3pricing.api.ExpirySlowMovingEvaluationResult;
import lk.coopfed.knoweb.m3pricing.internal.markdown.EvaluateExpirySlowMovingDiscountsHandler;
import lk.coopfed.knoweb.m3pricing.query.ExpirySlowMovingDiscountView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m5inventory.InventoryFixture;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.testsupport.KernelRecorder;
import lk.coopfed.knoweb.testsupport.OuterCommand;
import lk.coopfed.knoweb.testsupport.PinnedClock;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@Import(PinnedClock.class)
class ExpirySlowMovingDiscountsPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final UUID MPCS = UUID.fromString("0190e750-0000-7000-8000-000000000002");
    private static final UUID STRANGER = UUID.fromString("0190e750-0000-7000-8000-000000000088");

    @Autowired
    EvaluateExpirySlowMovingDiscountsHandler evaluateHandler;

    @Autowired
    PricingQueries pricingQueries;

    @Autowired
    StockLedger ledger;

    @Autowired
    OuterCommand outer;

    @Autowired
    Clock clock;

    private InventoryFixture fixture;
    private UUID shop;
    private UUID strangerShop;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        fixture = new InventoryFixture(superuserJdbc());
        fixture.clean();
        superuserJdbc()
                .execute("truncate table pricing.price_list_line, pricing.price_list, pricing.discount_rule cascade");
        fixture.entity(MPCS, "M3P1", "MPCS");
        fixture.entity(STRANGER, "M3ST", "MPCS");
        shop = fixture.location(MPCS, "SHOP");
        strangerShop = fixture.location(STRANGER, "SHOP");
        today = LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Colombo"));
        kernel.reset();
    }

    @AfterEach
    void tearDown() {
        superuserJdbc()
                .execute("truncate table pricing.price_list_line, pricing.price_list, pricing.discount_rule cascade");
        fixture.clean();
    }

    @Test
    void evaluatesNearExpiryAndSlowMovingDiscountsWithAuditAndDomainEvent() {
        ScopeContext scope = InventoryFixture.own(MPCS);

        // 1. Critical near-expiry lot: 3 days to expiry (<= 7)
        UUID sku1 = fixture.sku(MPCS, "M3_MILK_CRIT");
        UUID batch1 = fixture.batch(sku1, MPCS, "B-CRIT-01", today.plusDays(3));
        postReceipt(shop, batch1, "10.000", "150.00", MPCS);

        // Published retail price list in force for sku1
        UUID listId = Ids.next();
        superuserJdbc()
                .update(
                        "insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status, apply_from)"
                                + " values (?, ?, ?, 'RETAIL', 'Shelf', 1, 'DRAFT', ?)",
                        listId,
                        listId,
                        MPCS,
                        today.minusDays(10));
        superuserJdbc()
                .update(
                        "insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)"
                                + " values (?, ?, ?, 'EA', 0.000, 200.00, ?, ?)",
                        Ids.next(),
                        listId,
                        sku1,
                        today.minusDays(10),
                        MPCS);
        superuserJdbc()
                .update(
                        "update pricing.price_list set status = 'PUBLISHED', published_at = now() where price_list_id = ?",
                        listId);

        // 2. Warning near-expiry lot: 18 days to expiry (<= 30)
        // With recent sales, so velocity is normal (not stagnant)
        UUID sku2 = fixture.sku(MPCS, "M3_BREAD_WARN");
        UUID batch2 = fixture.batch(sku2, MPCS, "B-WARN-01", today.plusDays(18));
        postReceipt(shop, batch2, "20.000", "80.00", MPCS);
        postSale(shop, batch2, "-10.000", MPCS);

        // 3. Expired lot: -2 days (must be excluded from discounts)
        UUID sku3 = fixture.sku(MPCS, "M3_YOGURT_EXP");
        UUID batch3 = fixture.batch(sku3, MPCS, "B-EXP-01", today.minusDays(2));
        postReceipt(shop, batch3, "5.000", "50.00", MPCS);

        // 4. Slow-moving stock: in-date/non-expiring SKU with zero sales in window (STAGNANT)
        UUID sku4 = fixture.sku(MPCS, "M3_BISCUIT_SLOW");
        UUID batch4 = fixture.batch(sku4, MPCS, "B-SLOW-01", null);
        postReceipt(shop, batch4, "50.000", "200.00", MPCS);

        // Author active discount rules for sku1, sku2, and sku4 (Eligibility != Activation)
        insertRule(MPCS, "Milk Expiry Rule", "EXPIRY_MARKDOWN", sku1, 30, "30.00", today.minusDays(5), null);
        insertRule(MPCS, "Bread Expiry Rule", "EXPIRY_MARKDOWN", sku2, 30, "15.00", today.minusDays(5), null);
        insertRule(MPCS, "Biscuit Slow Rule", "TIME_LIMITED_PRICE", sku4, null, "20.00", today.minusDays(5), null);

        // Act: Execute discount evaluation handler
        ExpirySlowMovingEvaluationResult result =
                evaluateHandler.handle(new EvaluateExpirySlowMovingDiscounts(shop), scope);

        // Assert: Result summary
        assertThat(result.locationId()).isEqualTo(shop);
        assertThat(result.markdownsActive()).isEqualTo(3);
        assertThat(result.nearExpiryCount()).isEqualTo(2);
        assertThat(result.slowMovingCount()).isEqualTo(1);

        // Assert: Audit trail
        assertThat(audits(EvaluateExpirySlowMovingDiscountsHandler.AUDIT_EVALUATED))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.subject().id()).isEqualTo(shop);
                    assertThat(record.subject().type()).isEqualTo("location");
                });

        // Assert: Domain event published
        assertThat(events(ExpirySlowMovingDiscountsEvaluated.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.locationId()).isEqualTo(shop);
                    assertThat(event.ownerEntityId()).isEqualTo(MPCS);
                    assertThat(event.markdownsActive()).isEqualTo(result.markdownsActive());
                });

        // Query seam: list active markdowns through PricingQueries
        List<ExpirySlowMovingDiscountView> activeDiscounts =
                pricingQueries.listExpirySlowMovingDiscounts(shop, null, "ACTIVE", scope);

        assertThat(activeDiscounts).hasSize(3);
        assertThat(activeDiscounts).noneMatch(d -> d.skuId().equals(sku3));

        ExpirySlowMovingDiscountView criticalItem = activeDiscounts.stream()
                .filter(d -> d.skuId().equals(sku1))
                .findFirst()
                .orElseThrow();
        assertThat(criticalItem.reason()).isEqualTo("NEAR_EXPIRY");
        assertThat(criticalItem.tierOrClassification()).isEqualTo("CRITICAL");
        assertThat(criticalItem.markdownPercent()).isEqualByComparingTo("30.00");
        assertThat(criticalItem.discountedPrice()).isLessThan(criticalItem.basePrice());

        ExpirySlowMovingDiscountView warningItem = activeDiscounts.stream()
                .filter(d -> d.skuId().equals(sku2))
                .findFirst()
                .orElseThrow();
        assertThat(warningItem.reason()).isEqualTo("NEAR_EXPIRY");
        assertThat(warningItem.tierOrClassification()).isEqualTo("WARNING");
        assertThat(warningItem.markdownPercent()).isEqualByComparingTo("15.00");
        assertThat(warningItem.discountedPrice()).isLessThan(warningItem.basePrice());

        ExpirySlowMovingDiscountView slowItem = activeDiscounts.stream()
                .filter(d -> d.skuId().equals(sku4))
                .findFirst()
                .orElseThrow();
        assertThat(slowItem.reason()).isEqualTo("SLOW_MOVING");
        assertThat(slowItem.tierOrClassification()).isEqualTo("STAGNANT");
        assertThat(slowItem.markdownPercent()).isEqualByComparingTo("20.00");
        assertThat(slowItem.discountedPrice()).isLessThan(slowItem.basePrice());

        // POS retail price resolution consumes active markdown calculation for eligible items
        Optional<lk.coopfed.knoweb.m3pricing.query.RetailPrice> resolvedRetail =
                pricingQueries.resolveRetailPrice(shop, sku1, "EA", BigDecimal.ONE, today, scope);
        assertThat(resolvedRetail).isPresent();
        assertThat(resolvedRetail.get().unitPrice()).isEqualByComparingTo(criticalItem.discountedPrice());
        assertThat(resolvedRetail.get().unitPrice()).isLessThan(criticalItem.basePrice());
    }

    @Test
    void evaluationRejectsLocationNotOwnedByCaller() {
        ScopeContext mpcsScope = InventoryFixture.own(MPCS);

        // MPCS attempts to evaluate stranger entity's shop
        assertThatThrownBy(() -> evaluateHandler.handle(new EvaluateExpirySlowMovingDiscounts(strangerShop), mpcsScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("m3.label.location_not_owned");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void federationViewCannotExecuteEvaluation() {
        UUID fedEntity = UUID.fromString("0190e750-0000-7000-8000-000000000001");
        ScopeContext fedViewScope = viewer(fedEntity);

        // FEDERATION_VIEW caller is rejected because write mutation requires OWN class
        assertThatThrownBy(() -> evaluateHandler.handle(new EvaluateExpirySlowMovingDiscounts(shop), fedViewScope))
                .isInstanceOf(ProblemException.class)
                .hasMessage("scope.invalid");

        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void federationViewCanReadActiveMarkdowns() {
        ScopeContext ownScope = InventoryFixture.own(MPCS);
        UUID sku = fixture.sku(MPCS, "M3_TEA_FED");
        UUID batch = fixture.batch(sku, MPCS, "B-FED-01", today.plusDays(4));
        postReceipt(shop, batch, "10.000", "300.00", MPCS);
        insertRule(MPCS, "Tea Rule", "EXPIRY_MARKDOWN", sku, 30, "20.00", today.minusDays(5), null);

        UUID fedEntity = UUID.fromString("0190e750-0000-7000-8000-000000000001");
        ScopeContext fedViewScope = viewer(fedEntity);

        List<ExpirySlowMovingDiscountView> discounts =
                pricingQueries.listExpirySlowMovingDiscounts(shop, null, "ACTIVE", fedViewScope);
        assertThat(discounts).isNotEmpty();
    }

    private static ScopeContext viewer(UUID entity) {
        Scope scope = new Scope(entity, null);
        return new ScopeContext(
                Ids.next(),
                null,
                entity,
                List.of(scope),
                scope,
                PolicyClass.FEDERATION_VIEW,
                Set.of(),
                null,
                Locale.ENGLISH,
                null);
    }

    private void postReceipt(UUID location, UUID batch, String qty, String cost, UUID entity) {
        ScopeContext scope = InventoryFixture.own(entity);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        location,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.RECEIPT,
                                        new BigDecimal(qty),
                                        new BigDecimal(cost),
                                        null))),
                        scope));
    }

    private void postSale(UUID location, UUID batch, String qty, UUID entity) {
        ScopeContext scope = InventoryFixture.own(entity);
        outer.run(
                scope,
                () -> ledger.post(
                        new PostMovements(
                                Ids.next(),
                                null,
                                null,
                                List.of(new Movement(
                                        location,
                                        batch,
                                        LotCondition.GOOD,
                                        MovementType.SALE,
                                        new BigDecimal(qty),
                                        null,
                                        null))),
                        scope));
    }

    private List<KernelRecorder.AuditRecord> audits(String type) {
        return kernel.committedAudit().stream()
                .filter(a -> type.equals(a.eventType()))
                .toList();
    }

    private <T extends DomainEvent> List<T> events(Class<T> type) {
        return kernel.committedEvents().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    @Test
    void eligibleCandidateWithoutActiveRuleIsNotActivatedAndRetainsBasePrice() {
        ScopeContext scope = InventoryFixture.own(MPCS);

        // Near-expiry candidate with 3 days to expiry
        UUID sku = fixture.sku(MPCS, "M3_CANDIDATE_NO_RULE");
        UUID batch = fixture.batch(sku, MPCS, "B-NORULE-01", today.plusDays(3));
        postReceipt(shop, batch, "10.000", "150.00", MPCS);

        // Published retail price list in force for sku (shelf price 250.00)
        UUID listId = Ids.next();
        superuserJdbc()
                .update(
                        "insert into pricing.price_list (price_list_id, root_price_list_id, owner_entity_id, kind, name, version, status, apply_from)"
                                + " values (?, ?, ?, 'RETAIL', 'Shelf', 1, 'DRAFT', ?)",
                        listId,
                        listId,
                        MPCS,
                        today.minusDays(10));
        superuserJdbc()
                .update(
                        "insert into pricing.price_list_line (line_id, price_list_id, sku_id, uom_code, tier_from_qty, price, effective_from, owner_entity_id)"
                                + " values (?, ?, ?, 'EA', 0.000, 250.00, ?, ?)",
                        Ids.next(),
                        listId,
                        sku,
                        today.minusDays(10),
                        MPCS);
        superuserJdbc()
                .update(
                        "update pricing.price_list set status = 'PUBLISHED', published_at = now() where price_list_id = ?",
                        listId);

        // Notice: NO active discount rule authored for this SKU!

        // Query active markdowns: must NOT include this candidate (Eligibility != Activation)
        List<ExpirySlowMovingDiscountView> activeDiscounts =
                pricingQueries.listExpirySlowMovingDiscounts(shop, null, "ACTIVE", scope);
        assertThat(activeDiscounts).noneMatch(d -> d.skuId().equals(sku));

        // Retail price resolution: retains base price (250.00) unchanged
        Optional<lk.coopfed.knoweb.m3pricing.query.RetailPrice> price =
                pricingQueries.resolveRetailPrice(shop, sku, "EA", BigDecimal.ONE, today, scope);
        assertThat(price).isPresent();
        assertThat(price.get().unitPrice()).isEqualByComparingTo("250.00");
    }

    private void insertRule(
            UUID owner,
            String name,
            String kind,
            UUID skuId,
            Integer daysToExpiry,
            String percentOff,
            LocalDate from,
            LocalDate to) {
        UUID ruleId = Ids.next();
        String predicateJson = daysToExpiry != null
                ? String.format(Locale.ROOT, "{\"sku_id\":\"%s\",\"days_to_expiry\":%d}", skuId, daysToExpiry)
                : String.format(Locale.ROOT, "{\"sku_id\":\"%s\"}", skuId);
        String benefitJson = String.format(Locale.ROOT, "{\"kind\":\"PERCENT_OFF\",\"value\":\"%s\"}", percentOff);
        superuserJdbc()
                .update(
                        """
                        insert into pricing.discount_rule (rule_id, owner_entity_id, name, kind, predicate, benefit, priority, valid_from, valid_to, status)
                        values (?, ?, ?, ?, ?::jsonb, ?::jsonb, 100, ?, ?, 'ACTIVE')
                        """,
                        ruleId,
                        owner,
                        name,
                        kind,
                        predicateJson,
                        benefitJson,
                        java.sql.Date.valueOf(from),
                        to == null ? null : java.sql.Date.valueOf(to));
    }
}
