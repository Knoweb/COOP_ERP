package lk.coopfed.knoweb.m3pricing.internal.markdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.api.InventoryCandidateQuery;
import lk.coopfed.knoweb.m3pricing.api.InventoryCandidateQuery.NearExpiryCandidate;
import lk.coopfed.knoweb.m3pricing.api.InventoryCandidateQuery.SlowMovingCandidate;
import lk.coopfed.knoweb.m3pricing.internal.queries.RetailPriceResolution;
import lk.coopfed.knoweb.m3pricing.query.ExpirySlowMovingDiscountView;
import lk.coopfed.knoweb.m3pricing.query.RetailPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExpirySlowMovingDiscountCalculatorTest {

    private InventoryCandidateQuery inventoryCandidates;
    private RetailPriceResolution retailResolution;
    private CatalogueQueries catalogue;
    private ConfigRegistry config;
    private Clock clock;
    private ScopeContext scope;
    private ExpirySlowMovingDiscountCalculator calculator;

    private final UUID entityId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 11, 2);

    @BeforeEach
    void setUp() {
        inventoryCandidates = mock(InventoryCandidateQuery.class);
        retailResolution = mock(RetailPriceResolution.class);
        catalogue = mock(CatalogueQueries.class);
        config = mock(ConfigRegistry.class);
        clock = Clock.fixed(Instant.parse("2026-11-02T06:00:00Z"), ZoneOffset.UTC);
        scope = ScopeContext.dev(UUID.randomUUID(), entityId, locationId);

        // Default configs enabled
        when(config.getBoolean(eq(ExpirySlowMovingDiscountCalculator.KEY_ENABLED), any(), eq(true)))
                .thenReturn(true);
        when(config.get(eq(ExpirySlowMovingDiscountCalculator.KEY_CRITICAL_PERCENT), any()))
                .thenReturn(Optional.of("30.00"));
        when(config.get(eq(ExpirySlowMovingDiscountCalculator.KEY_WARNING_PERCENT), any()))
                .thenReturn(Optional.of("15.00"));
        when(config.get(eq(ExpirySlowMovingDiscountCalculator.KEY_STAGNANT_PERCENT), any()))
                .thenReturn(Optional.of("20.00"));
        when(config.get(eq(ExpirySlowMovingDiscountCalculator.KEY_SLOW_VELOCITY_PERCENT), any()))
                .thenReturn(Optional.of("10.00"));
        when(config.get(eq(ExpirySlowMovingDiscountCalculator.KEY_MAX_MARKDOWN_PERCENT), any()))
                .thenReturn(Optional.of("50.00"));

        calculator = new ExpirySlowMovingDiscountCalculator(
                inventoryCandidates, retailResolution, catalogue, config, clock, "UTC");
    }

    @Test
    @DisplayName("Disabled feature flag answers empty list immediately")
    void disabledFeatureFlagAnswersEmpty() {
        when(config.getBoolean(eq(ExpirySlowMovingDiscountCalculator.KEY_ENABLED), any(), eq(true)))
                .thenReturn(false);

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Near-expiry calculates critical and warning tiers with base price resolution")
    void nearExpiryCriticalAndWarningTiers() {
        UUID sku1 = UUID.randomUUID();
        UUID sku2 = UUID.randomUUID();
        UUID lot1 = UUID.randomUUID();
        UUID lot2 = UUID.randomUUID();

        when(catalogue.getSku(eq(sku1), any())).thenReturn(Optional.of(mockSku(sku1, "SKU-01", "KG")));
        when(catalogue.getSku(eq(sku2), any())).thenReturn(Optional.of(mockSku(sku2, "SKU-02", "PCS")));

        when(retailResolution.resolveBase(eq(locationId), eq(sku1), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku1, new BigDecimal("200.00"))));
        when(retailResolution.resolveBase(eq(locationId), eq(sku2), eq("PCS"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku2, new BigDecimal("100.00"))));

        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any()))
                .thenReturn(List.of(
                        new NearExpiryCandidate(
                                lot1,
                                locationId,
                                sku1,
                                UUID.randomUUID(),
                                "B-001",
                                today.plusDays(3),
                                3,
                                new BigDecimal("10.000"),
                                new BigDecimal("120.0000"),
                                "CRITICAL",
                                true),
                        new NearExpiryCandidate(
                                lot2,
                                locationId,
                                sku2,
                                UUID.randomUUID(),
                                "B-002",
                                today.plusDays(15),
                                15,
                                new BigDecimal("5.000"),
                                new BigDecimal("70.0000"),
                                "WARNING",
                                true)));
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any())).thenReturn(List.of());

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).hasSize(2);

        ExpirySlowMovingDiscountView criticalItem = results.stream()
                .filter(r -> r.stockLotId().equals(lot1))
                .findFirst()
                .orElseThrow();
        assertThat(criticalItem.reason()).isEqualTo("NEAR_EXPIRY");
        assertThat(criticalItem.tierOrClassification()).isEqualTo("CRITICAL");
        assertThat(criticalItem.basePrice()).isEqualByComparingTo("200.00");
        assertThat(criticalItem.markdownPercent()).isEqualByComparingTo("30.00");
        assertThat(criticalItem.discountedPrice()).isEqualByComparingTo("140.00");

        ExpirySlowMovingDiscountView warningItem = results.stream()
                .filter(r -> r.stockLotId().equals(lot2))
                .findFirst()
                .orElseThrow();
        assertThat(warningItem.reason()).isEqualTo("NEAR_EXPIRY");
        assertThat(warningItem.tierOrClassification()).isEqualTo("WARNING");
        assertThat(warningItem.basePrice()).isEqualByComparingTo("100.00");
        assertThat(warningItem.markdownPercent()).isEqualByComparingTo("15.00");
        assertThat(warningItem.discountedPrice()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("Expired and zero-stock lots are excluded from automated markdowns")
    void expiredAndZeroStockLotsExcluded() {
        UUID sku1 = UUID.randomUUID();
        when(catalogue.getSku(eq(sku1), any())).thenReturn(Optional.of(mockSku(sku1, "SKU-01", "KG")));

        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any()))
                .thenReturn(List.of(
                        new NearExpiryCandidate(
                                UUID.randomUUID(),
                                locationId,
                                sku1,
                                UUID.randomUUID(),
                                "B-EXPIRED",
                                today.minusDays(1),
                                -1,
                                new BigDecimal("10.000"),
                                new BigDecimal("100.0000"),
                                "EXPIRED",
                                true),
                        new NearExpiryCandidate(
                                UUID.randomUUID(),
                                locationId,
                                sku1,
                                UUID.randomUUID(),
                                "B-ZERO",
                                today.plusDays(2),
                                2,
                                BigDecimal.ZERO,
                                new BigDecimal("100.0000"),
                                "CRITICAL",
                                true)));
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any())).thenReturn(List.of());

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Slow-moving inventory calculates stagnant and slow velocity discounts")
    void slowMovingStagnantAndSlowVelocity() {
        UUID sku1 = UUID.randomUUID();
        UUID sku2 = UUID.randomUUID();

        when(catalogue.getSku(eq(sku1), any())).thenReturn(Optional.of(mockSku(sku1, "SKU-STAGNANT", "KG")));
        when(catalogue.getSku(eq(sku2), any())).thenReturn(Optional.of(mockSku(sku2, "SKU-SLOW", "PCS")));

        when(retailResolution.resolveBase(eq(locationId), eq(sku1), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku1, new BigDecimal("500.00"))));
        when(retailResolution.resolveBase(eq(locationId), eq(sku2), eq("PCS"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku2, new BigDecimal("300.00"))));

        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any())).thenReturn(List.of());
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any()))
                .thenReturn(List.of(
                        new SlowMovingCandidate(
                                locationId,
                                sku1,
                                new BigDecimal("40.000"),
                                new BigDecimal("350.0000"),
                                120,
                                today.minusDays(120),
                                BigDecimal.TEN,
                                BigDecimal.ONE,
                                new BigDecimal("30"),
                                "STAGNANT",
                                true),
                        new SlowMovingCandidate(
                                locationId,
                                sku2,
                                new BigDecimal("25.000"),
                                new BigDecimal("200.0000"),
                                45,
                                today.minusDays(45),
                                BigDecimal.TEN,
                                BigDecimal.ONE,
                                new BigDecimal("15"),
                                "SLOW_VELOCITY",
                                true)));

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).hasSize(2);

        ExpirySlowMovingDiscountView stagnant =
                results.stream().filter(r -> r.skuId().equals(sku1)).findFirst().orElseThrow();
        assertThat(stagnant.reason()).isEqualTo("SLOW_MOVING");
        assertThat(stagnant.tierOrClassification()).isEqualTo("STAGNANT");
        assertThat(stagnant.markdownPercent()).isEqualByComparingTo("20.00");
        assertThat(stagnant.discountedPrice()).isEqualByComparingTo("400.00");

        ExpirySlowMovingDiscountView slowVelocity =
                results.stream().filter(r -> r.skuId().equals(sku2)).findFirst().orElseThrow();
        assertThat(slowVelocity.reason()).isEqualTo("SLOW_MOVING");
        assertThat(slowVelocity.tierOrClassification()).isEqualTo("SLOW_VELOCITY");
        assertThat(slowVelocity.markdownPercent()).isEqualByComparingTo("10.00");
        assertThat(slowVelocity.discountedPrice()).isEqualByComparingTo("270.00");
    }

    @Test
    @DisplayName("Non-stacking conflict resolution: imminent critical expiry takes precedence")
    void nonStackingConflictResolutionCriticalExpiryWins() {
        UUID sku1 = UUID.randomUUID();
        UUID lot1 = UUID.randomUUID();

        when(catalogue.getSku(eq(sku1), any())).thenReturn(Optional.of(mockSku(sku1, "SKU-BOTH", "KG")));
        when(retailResolution.resolveBase(eq(locationId), eq(sku1), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku1, new BigDecimal("100.00"))));

        // Near-expiry candidate is CRITICAL (30%)
        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any()))
                .thenReturn(List.of(new NearExpiryCandidate(
                        lot1,
                        locationId,
                        sku1,
                        UUID.randomUUID(),
                        "B-CRITICAL",
                        today.plusDays(2),
                        2,
                        new BigDecimal("10.000"),
                        new BigDecimal("60.0000"),
                        "CRITICAL",
                        true)));

        // Same SKU is also classified as STAGNANT (20%)
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any()))
                .thenReturn(List.of(new SlowMovingCandidate(
                        locationId,
                        sku1,
                        new BigDecimal("10.000"),
                        new BigDecimal("60.0000"),
                        150,
                        today.minusDays(150),
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        new BigDecimal("30"),
                        "STAGNANT",
                        true)));

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        // Conflict resolution: only 1 markdown exists, CRITICAL near-expiry preserved
        assertThat(results).hasSize(1);
        ExpirySlowMovingDiscountView item = results.get(0);
        assertThat(item.reason()).isEqualTo("NEAR_EXPIRY");
        assertThat(item.tierOrClassification()).isEqualTo("CRITICAL");
        assertThat(item.markdownPercent()).isEqualByComparingTo("30.00");
        assertThat(item.discountedPrice()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("Non-stacking conflict resolution: stronger slow-moving markdown wins over warning tier")
    void nonStackingConflictResolutionStrongerSlowMovingWinsOverWarning() {
        UUID sku1 = UUID.randomUUID();
        UUID lot1 = UUID.randomUUID();

        when(catalogue.getSku(eq(sku1), any())).thenReturn(Optional.of(mockSku(sku1, "SKU-BOTH", "KG")));
        when(retailResolution.resolveBase(eq(locationId), eq(sku1), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku1, new BigDecimal("100.00"))));

        // Near-expiry is WARNING (15%)
        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any()))
                .thenReturn(List.of(new NearExpiryCandidate(
                        lot1,
                        locationId,
                        sku1,
                        UUID.randomUUID(),
                        "B-WARN",
                        today.plusDays(14),
                        14,
                        new BigDecimal("10.000"),
                        new BigDecimal("60.0000"),
                        "WARNING",
                        true)));

        // Slow-moving is STAGNANT (20% > 15%)
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any()))
                .thenReturn(List.of(new SlowMovingCandidate(
                        locationId,
                        sku1,
                        new BigDecimal("10.000"),
                        new BigDecimal("60.0000"),
                        120,
                        today.minusDays(120),
                        BigDecimal.TEN,
                        BigDecimal.ONE,
                        new BigDecimal("30"),
                        "STAGNANT",
                        true)));

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        // Stronger 20% markdown wins
        assertThat(results).hasSize(1);
        ExpirySlowMovingDiscountView item = results.get(0);
        assertThat(item.reason()).isEqualTo("SLOW_MOVING");
        assertThat(item.tierOrClassification()).isEqualTo("STAGNANT");
        assertThat(item.markdownPercent()).isEqualByComparingTo("20.00");
        assertThat(item.discountedPrice()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("Markdown percent is capped at max markdown ceiling")
    void markdownPercentCappedAtCeiling() {
        UUID sku1 = UUID.randomUUID();
        when(catalogue.getSku(eq(sku1), any())).thenReturn(Optional.of(mockSku(sku1, "SKU-01", "KG")));
        when(retailResolution.resolveBase(eq(locationId), eq(sku1), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku1, new BigDecimal("100.00"))));

        // Max markdown capped at 25%
        when(config.get(eq(ExpirySlowMovingDiscountCalculator.KEY_MAX_MARKDOWN_PERCENT), any()))
                .thenReturn(Optional.of("25.00"));

        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any()))
                .thenReturn(List.of(new NearExpiryCandidate(
                        UUID.randomUUID(),
                        locationId,
                        sku1,
                        UUID.randomUUID(),
                        "B-01",
                        today.plusDays(2),
                        2,
                        new BigDecimal("5.000"),
                        new BigDecimal("50.0000"),
                        "CRITICAL",
                        true)));
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any())).thenReturn(List.of());

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).markdownPercent()).isEqualByComparingTo("25.00");
        assertThat(results.get(0).discountedPrice()).isEqualByComparingTo("75.00");
    }

    @Test
    @DisplayName("Slow-moving markdown permits below-cost price as no authoritative cost floor exists in repository")
    void slowMovingMarkdownPermitsBelowCostWhenNoCostFloorExists() {
        UUID sku = UUID.randomUUID();
        when(catalogue.getSku(eq(sku), any())).thenReturn(Optional.of(mockSku(sku, "SKU-SLOW-BELOW-COST", "KG")));
        // Base shelf price: 100.00, Unit cost: 85.00
        when(retailResolution.resolveBase(eq(locationId), eq(sku), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku, new BigDecimal("100.00"))));

        // Stagnant stock: 20% markdown yields 80.00 (below cost of 85.00).
        // Since no cost floor rule exists, below-cost discount is not blocked.
        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any())).thenReturn(List.of());
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any()))
                .thenReturn(List.of(new SlowMovingCandidate(
                        locationId,
                        sku,
                        new BigDecimal("20.000"),
                        new BigDecimal("85.0000"),
                        45,
                        null,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        new BigDecimal("999.0"),
                        "STAGNANT",
                        false)));

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).hasSize(1);
        ExpirySlowMovingDiscountView item = results.get(0);
        assertThat(item.basePrice()).isEqualByComparingTo("100.00");
        assertThat(item.discountedPrice()).isEqualByComparingTo("80.00");
        assertThat(item.discountedPrice()).isLessThan(new BigDecimal("85.00")); // Below cost permitted
        assertThat(item.markdownPercent()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("Discount is skipped when base price is at or below the minimum price floor (0.01)")
    void markdownSkippedWhenBasePriceAtOrBelowMinimumFloor() {
        UUID sku = UUID.randomUUID();
        when(catalogue.getSku(eq(sku), any())).thenReturn(Optional.of(mockSku(sku, "SKU-TINY", "KG")));
        // Base shelf price: 0.01 (already at minimum allowed currency unit)
        when(retailResolution.resolveBase(eq(locationId), eq(sku), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku, new BigDecimal("0.01"))));

        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any()))
                .thenReturn(List.of(new NearExpiryCandidate(
                        UUID.randomUUID(),
                        locationId,
                        sku,
                        UUID.randomUUID(),
                        "B-TINY-01",
                        today.plusDays(2),
                        2,
                        new BigDecimal("10.000"),
                        new BigDecimal("85.0000"),
                        "CRITICAL",
                        false)));
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any())).thenReturn(List.of());

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Near-expiry salvage permits below-cost markdown as no cost floor exists")
    void nearExpirySalvagePermitsBelowCostMarkdown() {
        UUID sku = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        when(catalogue.getSku(eq(sku), any())).thenReturn(Optional.of(mockSku(sku, "SKU-PERISH", "KG")));
        // Base shelf price: 100.00, Unit cost: 85.00
        when(retailResolution.resolveBase(eq(locationId), eq(sku), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku, new BigDecimal("100.00"))));

        // Critical near-expiry (30% markdown -> 70.00). Below cost of 85.00, but permitted for perishables.
        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any()))
                .thenReturn(List.of(new NearExpiryCandidate(
                        lot,
                        locationId,
                        sku,
                        UUID.randomUUID(),
                        "B-PERISH-01",
                        today.plusDays(2),
                        2,
                        new BigDecimal("10.000"),
                        new BigDecimal("85.0000"),
                        "CRITICAL",
                        false)));
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any())).thenReturn(List.of());

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).hasSize(1);
        ExpirySlowMovingDiscountView item = results.get(0);
        assertThat(item.basePrice()).isEqualByComparingTo("100.00");
        assertThat(item.discountedPrice()).isEqualByComparingTo("70.00");
        assertThat(item.discountedPrice()).isLessThan(new BigDecimal("85.00")); // Below cost permitted for salvage
        assertThat(item.markdownPercent()).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("Discounted price is clamped at minimum price floor (0.01) rather than zero")
    void discountClampedAtMinimumPriceFloor() {
        UUID sku = UUID.randomUUID();
        when(catalogue.getSku(eq(sku), any())).thenReturn(Optional.of(mockSku(sku, "SKU-LOW", "KG")));
        // Base shelf price: 0.02
        when(retailResolution.resolveBase(eq(locationId), eq(sku), eq("KG"), any(), any(), any()))
                .thenReturn(Optional.of(mockRetailPrice(locationId, sku, new BigDecimal("0.02"))));

        when(inventoryCandidates.nearExpiryCandidates(eq(locationId), any()))
                .thenReturn(List.of(new NearExpiryCandidate(
                        UUID.randomUUID(),
                        locationId,
                        sku,
                        UUID.randomUUID(),
                        "B-LOW-01",
                        today.plusDays(2),
                        2,
                        new BigDecimal("10.000"),
                        new BigDecimal("0.0500"),
                        "CRITICAL",
                        false)));
        when(inventoryCandidates.slowMovingCandidates(eq(locationId), any())).thenReturn(List.of());

        List<ExpirySlowMovingDiscountView> results = calculator.calculate(locationId, null, null, scope);

        assertThat(results).hasSize(1);
        ExpirySlowMovingDiscountView item = results.get(0);
        assertThat(item.discountedPrice()).isEqualByComparingTo("0.01");
        assertThat(item.discountedPrice()).isGreaterThan(BigDecimal.ZERO);
    }

    private SkuView mockSku(UUID skuId, String skuCode, String baseUom) {
        return new SkuView(
                skuId,
                skuCode,
                entityId,
                "ACTIVE",
                "Name EN",
                "Name SI",
                "Name TA",
                null,
                null,
                null,
                baseUom,
                false,
                true,
                true,
                true,
                (short) 30,
                null,
                "AUTO_LOWEST",
                "LOCAL",
                Map.of());
    }

    private RetailPrice mockRetailPrice(UUID locId, UUID skuId, BigDecimal price) {
        return new RetailPrice(
                locId,
                skuId,
                "KG",
                true,
                null,
                UUID.randomUUID(),
                price,
                price,
                null,
                null,
                "NONE",
                "AUTO_LOWEST",
                null,
                "v1");
    }
}
