package lk.coopfed.knoweb.m3pricing.internal.markdown;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.api.InventoryCandidateQuery;
import lk.coopfed.knoweb.m3pricing.api.InventoryCandidateQuery.NearExpiryCandidate;
import lk.coopfed.knoweb.m3pricing.api.InventoryCandidateQuery.SlowMovingCandidate;
import lk.coopfed.knoweb.m3pricing.internal.queries.RetailPriceResolution;
import lk.coopfed.knoweb.m3pricing.internal.rule.RuleStore;
import lk.coopfed.knoweb.m3pricing.query.ExpirySlowMovingDiscountView;
import lk.coopfed.knoweb.m3pricing.query.RetailPrice;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic calculation engine for near-expiry and slow-moving discount
 * markdowns (PGL-M1-07).
 * Consumes authoritative inventory candidates via
 * {@link InventoryCandidateQuery} and applies
 * configurable markdown policies with priority conflict resolution and safety
 * ceilings.
 *
 * <p>Eligibility != Activation: Inventory candidates represent eligible items, but
 * markdowns are only active when an explicitly authored and active rule exists in
 * {@link RuleStore}.
 */
@Component
public class ExpirySlowMovingDiscountCalculator {

    public static final String KEY_ENABLED = "pricing.expiry_discounts_enabled";
    public static final String KEY_CRITICAL_PERCENT = "pricing.near_expiry_discount_critical_percent";
    public static final String KEY_WARNING_PERCENT = "pricing.near_expiry_discount_warning_percent";
    public static final String KEY_STAGNANT_PERCENT = "pricing.slow_moving_discount_stagnant_percent";
    public static final String KEY_SLOW_VELOCITY_PERCENT = "pricing.slow_moving_discount_slow_velocity_percent";
    public static final String KEY_MAX_MARKDOWN_PERCENT = "pricing.max_markdown_percent";

    private static final BigDecimal DEFAULT_CRITICAL_PERCENT = new BigDecimal("30.00");
    private static final BigDecimal DEFAULT_WARNING_PERCENT = new BigDecimal("15.00");
    private static final BigDecimal DEFAULT_STAGNANT_PERCENT = new BigDecimal("20.00");
    private static final BigDecimal DEFAULT_SLOW_VELOCITY_PERCENT = new BigDecimal("10.00");
    private static final BigDecimal DEFAULT_MAX_MARKDOWN_PERCENT = new BigDecimal("50.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MIN_PRICE = new BigDecimal("0.01");

    private final InventoryCandidateQuery inventoryCandidates;
    private final RetailPriceResolution retailResolution;
    private final CatalogueQueries catalogue;
    private final PartyQueries party;
    private final RuleStore ruleStore;
    private final ConfigRegistry config;
    private final Clock clock;
    private final ZoneId businessZone;

    public ExpirySlowMovingDiscountCalculator(
            InventoryCandidateQuery inventoryCandidates,
            RetailPriceResolution retailResolution,
            CatalogueQueries catalogue,
            PartyQueries party,
            RuleStore ruleStore,
            ConfigRegistry config,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.inventoryCandidates = inventoryCandidates;
        this.retailResolution = retailResolution;
        this.catalogue = catalogue;
        this.party = party;
        this.ruleStore = ruleStore;
        this.config = config;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    public List<ExpirySlowMovingDiscountView> calculate(
            UUID locationId, String reasonFilter, String statusFilter, ScopeContext scope) {
        if (!config.getBoolean(KEY_ENABLED, scope, true)) {
            return List.of();
        }

        LocalDate today = LocalDate.ofInstant(clock.instant(), businessZone);
        BigDecimal criticalPct = configPercent(KEY_CRITICAL_PERCENT, DEFAULT_CRITICAL_PERCENT, scope);
        BigDecimal warningPct = configPercent(KEY_WARNING_PERCENT, DEFAULT_WARNING_PERCENT, scope);
        BigDecimal stagnantPct = configPercent(KEY_STAGNANT_PERCENT, DEFAULT_STAGNANT_PERCENT, scope);
        BigDecimal slowVelocityPct = configPercent(KEY_SLOW_VELOCITY_PERCENT, DEFAULT_SLOW_VELOCITY_PERCENT, scope);
        BigDecimal maxMarkdownPct = configPercent(KEY_MAX_MARKDOWN_PERCENT, DEFAULT_MAX_MARKDOWN_PERCENT, scope);

        // Resolve owner entity id
        UUID ownerEntityId = null;
        if (party != null && locationId != null) {
            ownerEntityId = party.getLocation(locationId, scope)
                    .map(LocationView::ownerEntityId)
                    .orElse(scope.entityId());
        } else {
            ownerEntityId = scope.entityId();
        }

        // Active rules in force for this owner entity
        List<RuleView> activeRules = List.of();
        if (ruleStore != null) {
            final UUID owner = ownerEntityId;
            activeRules = ruleStore.activeOnOrAfter(today).stream()
                    .filter(r -> owner == null || r.ownerEntityId().equals(owner))
                    .filter(r -> !today.isBefore(r.validFrom()) && (r.validTo() == null || !today.isAfter(r.validTo())))
                    .toList();
        }

        Map<UUID, ExpirySlowMovingDiscountView> calculatedByLot = new HashMap<>();

        // 1. Evaluate near-expiry candidates
        List<NearExpiryCandidate> nearExpiryLots = inventoryCandidates.nearExpiryCandidates(locationId, scope);
        for (NearExpiryCandidate lot : nearExpiryLots) {
            // Guard: excluded expired, healthy, zero or negative stock
            if (lot.qtyOnHand() == null || lot.qtyOnHand().signum() <= 0) {
                continue;
            }
            if (lot.daysToExpiry() < 0 || "EXPIRED".equalsIgnoreCase(lot.urgencyTier())) {
                continue;
            }

            // Must match an active EXPIRY_MARKDOWN rule for this SKU and days to expiry
            Optional<RuleView> matchedRule = activeRules.stream()
                    .filter(r -> "EXPIRY_MARKDOWN".equalsIgnoreCase(r.kind()))
                    .filter(r -> lot.skuId().equals(r.predicate().skuId()))
                    .filter(r -> r.predicate().daysToExpiry() == null
                            || (lot.daysToExpiry() <= r.predicate().daysToExpiry() && lot.daysToExpiry() >= 0))
                    .findFirst();

            if (matchedRule.isEmpty()) {
                // Eligibility != Activation: Candidate is not active without an active rule
                continue;
            }

            RuleView rule = matchedRule.get();

            BigDecimal markdownPct;
            if (rule.benefit() != null
                    && rule.benefit().value() != null
                    && rule.benefit().value().signum() > 0) {
                markdownPct = rule.benefit().value();
            } else if ("CRITICAL".equalsIgnoreCase(lot.urgencyTier())) {
                markdownPct = criticalPct;
            } else if ("WARNING".equalsIgnoreCase(lot.urgencyTier())) {
                markdownPct = warningPct;
            } else {
                continue; // APPROACHING or HEALTHY has no automated discount
            }

            if (markdownPct.signum() <= 0) {
                continue;
            }

            markdownPct = markdownPct.min(maxMarkdownPct);

            BigDecimal basePrice = resolveBasePrice(lot.locationId(), lot.skuId(), lot.unitCost(), today, scope);
            if (basePrice == null || basePrice.compareTo(MIN_PRICE) <= 0) {
                // Base price is null or already at/below the minimum valid positive price
                // (0.01)
                continue;
            }

            BigDecimal discountAmount = basePrice.multiply(markdownPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal tentativePrice = basePrice.subtract(discountAmount);

            BigDecimal discountedPrice;
            BigDecimal effectiveMarkdownPct;

            if (tentativePrice.compareTo(MIN_PRICE) < 0) {
                // Positive price invariant (CR-23A-1 item 5): selling price cannot be 0.00 or negative
                discountedPrice = MIN_PRICE;
                effectiveMarkdownPct =
                        basePrice.subtract(MIN_PRICE).multiply(HUNDRED).divide(basePrice, 2, RoundingMode.HALF_UP);
            } else {
                discountedPrice = tentativePrice;
                effectiveMarkdownPct = markdownPct;
            }

            Optional<SkuView> sku = catalogue.getSku(lot.skuId(), scope);
            String skuCode = sku.map(SkuView::skuCode).orElse(lot.skuId().toString());

            LocalDate validTo = rule.validTo() != null
                    ? (lot.expiryDate() != null && lot.expiryDate().isBefore(rule.validTo())
                            ? lot.expiryDate()
                            : rule.validTo())
                    : lot.expiryDate();

            ExpirySlowMovingDiscountView view = new ExpirySlowMovingDiscountView(
                    lot.skuId(),
                    skuCode,
                    lot.stockLotId(),
                    lot.batchId(),
                    lot.batchNo(),
                    lot.locationId(),
                    "NEAR_EXPIRY",
                    lot.urgencyTier().toUpperCase(),
                    basePrice,
                    markdownPct,
                    discountedPrice,
                    lot.qtyOnHand(),
                    lot.expiryDate(),
                    lot.daysToExpiry(),
                    today,
                    validTo,
                    "ACTIVE");

            calculatedByLot.put(lot.stockLotId(), view);
        }

        // 2. Evaluate slow-moving candidates
        List<SlowMovingCandidate> slowMovingItems = inventoryCandidates.slowMovingCandidates(locationId, scope);
        for (SlowMovingCandidate item : slowMovingItems) {
            if (item.qtyOnHand() == null || item.qtyOnHand().signum() <= 0) {
                continue;
            }

            // Must match an active TIME_LIMITED_PRICE or SLOW_MOVING rule for this SKU
            Optional<RuleView> matchedRule = activeRules.stream()
                    .filter(r ->
                            "TIME_LIMITED_PRICE".equalsIgnoreCase(r.kind()) || "SLOW_MOVING".equalsIgnoreCase(r.kind()))
                    .filter(r -> item.skuId().equals(r.predicate().skuId()))
                    .findFirst();

            if (matchedRule.isEmpty()) {
                // Eligibility != Activation: Candidate is not active without an active rule
                continue;
            }

            RuleView rule = matchedRule.get();

            BigDecimal markdownPct;
            if (rule.benefit() != null
                    && rule.benefit().value() != null
                    && rule.benefit().value().signum() > 0) {
                markdownPct = rule.benefit().value();
            } else if ("STAGNANT".equalsIgnoreCase(item.classification())) {
                markdownPct = stagnantPct;
            } else if ("SLOW_VELOCITY".equalsIgnoreCase(item.classification())) {
                markdownPct = slowVelocityPct;
            } else {
                continue;
            }

            if (markdownPct.signum() <= 0) {
                continue;
            }

            markdownPct = markdownPct.min(maxMarkdownPct);

            BigDecimal basePrice = resolveBasePrice(item.locationId(), item.skuId(), item.unitCost(), today, scope);
            if (basePrice == null || basePrice.compareTo(MIN_PRICE) <= 0) {
                // Base price is null or already at/below the minimum valid positive price
                // (0.01)
                continue;
            }

            BigDecimal discountAmount = basePrice.multiply(markdownPct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal tentativePrice = basePrice.subtract(discountAmount);

            BigDecimal discountedPrice;
            BigDecimal effectiveMarkdownPct = markdownPct;

            if (tentativePrice.compareTo(MIN_PRICE) < 0) {
                // Positive price invariant (CR-23A-1 item 5): selling price cannot be 0.00 or
                // negative
                discountedPrice = MIN_PRICE;
                discountAmount = basePrice.subtract(MIN_PRICE);
                effectiveMarkdownPct = discountAmount.multiply(HUNDRED).divide(basePrice, 2, RoundingMode.HALF_UP);
            } else {
                discountedPrice = tentativePrice;
                effectiveMarkdownPct = markdownPct;
            }

            Optional<SkuView> sku = catalogue.getSku(item.skuId(), scope);
            String skuCode = sku.map(SkuView::skuCode).orElse(item.skuId().toString());

            LocalDate validTo = rule.validTo();

            ExpirySlowMovingDiscountView slowView = new ExpirySlowMovingDiscountView(
                    item.skuId(),
                    skuCode,
                    null, // Aggregated by SKU across lots
                    null,
                    null,
                    item.locationId(),
                    "SLOW_MOVING",
                    item.classification().toUpperCase(),
                    basePrice,
                    effectiveMarkdownPct,
                    discountedPrice,
                    item.qtyOnHand(),
                    null,
                    null,
                    today,
                    validTo,
                    "ACTIVE");

            // 3. Conflict resolution & non-stacking (Section 6)
            // If any near-expiry lots exist for this SKU, evaluate conflict
            boolean skuHasNearExpiry =
                    calculatedByLot.values().stream().anyMatch(v -> v.skuId().equals(item.skuId()));

            if (skuHasNearExpiry) {
                // If the near-expiry is CRITICAL, imminent perishability takes precedence.
                // Otherwise if slow-moving offers a stronger markdown, update matching lot views.
                for (Map.Entry<UUID, ExpirySlowMovingDiscountView> entry : calculatedByLot.entrySet()) {
                    ExpirySlowMovingDiscountView current = entry.getValue();
                    if (current.skuId().equals(item.skuId())) {
                        if (!"CRITICAL".equalsIgnoreCase(current.tierOrClassification())
                                && effectiveMarkdownPct.compareTo(current.markdownPercent()) > 0) {
                            // Stronger markdown from slow-moving wins
                            calculatedByLot.put(
                                    entry.getKey(),
                                    new ExpirySlowMovingDiscountView(
                                            current.skuId(),
                                            current.skuCode(),
                                            current.stockLotId(),
                                            current.batchId(),
                                            current.batchNo(),
                                            current.locationId(),
                                            "SLOW_MOVING",
                                            item.classification().toUpperCase(),
                                            current.basePrice(),
                                            effectiveMarkdownPct,
                                            discountedPrice,
                                            current.qtyOnHand(),
                                            current.expiryDate(),
                                            current.daysToExpiry(),
                                            today,
                                            current.validTo(),
                                            "ACTIVE"));
                        }
                    }
                }
            } else {
                // SKU-level slow-moving discount
                calculatedByLot.put(
                        UUID.nameUUIDFromBytes(("slow_" + item.locationId() + "_" + item.skuId()).getBytes()),
                        slowView);
            }
        }

        // 4. Filter and sort
        List<ExpirySlowMovingDiscountView> results = new ArrayList<>(calculatedByLot.values());

        if (reasonFilter != null && !reasonFilter.isBlank() && !"ALL".equalsIgnoreCase(reasonFilter)) {
            results = results.stream()
                    .filter(v -> reasonFilter.equalsIgnoreCase(v.reason()))
                    .toList();
        }

        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter)) {
            results = results.stream()
                    .filter(v -> statusFilter.equalsIgnoreCase(v.status()))
                    .toList();
        }

        results = new ArrayList<>(results);
        results.sort(Comparator.comparing(ExpirySlowMovingDiscountView::reason)
                .thenComparing(ExpirySlowMovingDiscountView::tierOrClassification)
                .thenComparing(v -> v.daysToExpiry() != null ? v.daysToExpiry() : 9999)
                .thenComparing(ExpirySlowMovingDiscountView::skuCode));

        return results;
    }

    private BigDecimal resolveBasePrice(
            UUID locationId, UUID skuId, BigDecimal fallbackUnitCost, LocalDate today, ScopeContext scope) {
        Optional<SkuView> sku = catalogue.getSku(skuId, scope);
        if (sku.isEmpty()) {
            return fallbackUnitCost;
        }

        String uom = sku.get().baseUomCode();
        Optional<RetailPrice> retail =
                retailResolution.resolveBase(locationId, skuId, uom, BigDecimal.ONE, today, scope);
        if (retail.isPresent() && retail.get().sellable() && retail.get().unitPrice() != null) {
            return retail.get().unitPrice();
        }
        if (retail.isPresent() && retail.get().listPrice() != null) {
            return retail.get().listPrice();
        }

        return fallbackUnitCost;
    }

    private BigDecimal configPercent(String key, BigDecimal defaultValue, ScopeContext scope) {
        return config.get(key, scope)
                .map(val -> {
                    try {
                        return new BigDecimal(val.trim());
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }
}
