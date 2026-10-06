package lk.coopfed.knoweb.m3pricing.internal.list;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lk.coopfed.knoweb.engine.Quantity;
import lk.coopfed.knoweb.engine.TradePriceResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BatchFilter;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.api.SetLines;
import lk.coopfed.knoweb.m3pricing.api.SetLinesResult.Outcome;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.ControlPriceStore;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.ShelfBatches;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import org.springframework.stereotype.Component;

/**
 * The authoring checks of a list's lines (23A section 7, SetLines and PublishPriceList: "per line:
 * sku active, uom valid (M2), tiers ascending, price >= 0 (a RETAIL price > 0, CR-23A-1);
 * AuthoringValidator.ceilings (RETAIL: min
 * in-stock batch MRP at owner's locations via M5 query, control price; TRADE: review-only above
 * lowest MRP)"). One outcome per line, in the order given.
 *
 * <p>RETAIL and ADVISORY (M3-06; doc 23 section 3.1: "a line's price never exceeds the ceilings at
 * publication"; flow 6.1: "a line above a ceiling is refused with the binding ceiling shown"): a
 * price above the control price in force on the date is refused, and for RETAIL a price above the
 * lowest printed MRP in stock at any of the society's locations too. Their prices are tax-inclusive
 * rupees with at most two decimals, and they carry no quantity tiers (the engine prices a retail
 * line per unit, whatever the quantity). An ADVISORY list is the Federation's and has no stock, so
 * only the control price binds it.
 *
 * <p>What it does not check yet, deferred: units other than the SKU's base unit (M2 publishes no
 * query of a SKU's conversions; a line is priced in the base unit until it does), and so no unit
 * conversion of a ceiling either (23A section 11).
 */
@Component
class AuthoringValidator {

    static final String REVIEW_ABOVE_MRP = "m3.price_list.review.above_mrp";
    static final String CEILING_CONTROL = "CONTROL_PRICE";
    static final String CEILING_MRP = "MRP";

    /** The SKU states in which it can be traded (doc 22 section 4: LOCAL and SHARED are active). */
    private static final Set<String> ACTIVE = Set.of("LOCAL", "SHARED");

    /** The newest batches of a SKU, which are those on the market: the largest page M2 serves (BatchFilter). */
    private static final int BATCHES_FOR_REVIEW = 200;

    private final CatalogueQueries catalogue;
    private final BatchQueries batches;
    private final ControlPriceStore controlPrices;
    private final ShelfBatches shelf;

    AuthoringValidator(
            CatalogueQueries catalogue, BatchQueries batches, ControlPriceStore controlPrices, ShelfBatches shelf) {
        this.catalogue = catalogue;
        this.batches = batches;
        this.controlPrices = controlPrices;
        this.shelf = shelf;
    }

    /**
     * The outcome of each line of a list of the kind, against the ceilings in force on the date
     * (today while drafting, apply_from at publication).
     */
    List<Outcome> check(String kind, List<SetLines.Line> lines, LocalDate onDate, ScopeContext scope) {
        boolean shelfList = !PriceListStore.TRADE.equals(kind);
        Map<UUID, Optional<SkuView>> skus = new HashMap<>();
        Map<UUID, Optional<BigDecimal>> lowestMrp = new HashMap<>();
        Map<UUID, Optional<ShelfBatches.Shelved>> lowestInStock = new HashMap<>();
        List<UUID> locations = PriceListStore.RETAIL.equals(kind) ? shelf.locationsOfCaller(scope) : List.of();
        Map<String, String> tierFaults = tierFaults(lines);
        Map<String, Long> occurrences = lines.stream()
                .filter(line -> line.skuId() != null && line.uomCode() != null && line.tierFromQty() != null)
                .collect(Collectors.groupingBy(AuthoringValidator::key, Collectors.counting()));

        List<Outcome> outcomes = new ArrayList<>();
        for (SetLines.Line line : lines) {
            String reason =
                    refusal(line, shelfList, PriceListStore.RETAIL.equals(kind), skus, tierFaults, occurrences, scope);
            if (reason != null) {
                outcomes.add(new Outcome(line.skuId(), line.uomCode(), line.tierFromQty(), false, reason, null));
                continue;
            }
            if (!shelfList) {
                BigDecimal mrp = lowestMrp
                        .computeIfAbsent(line.skuId(), sku -> lowestPrintedMrp(sku, scope))
                        .orElse(null);
                // doc 23 section 3.4 and doc 10 A-04: a review, not a block.
                boolean above = mrp != null && line.price().compareTo(mrp) > 0;
                outcomes.add(new Outcome(
                        line.skuId(),
                        line.uomCode(),
                        line.tierFromQty(),
                        true,
                        null,
                        above ? REVIEW_ABOVE_MRP : null,
                        above ? CEILING_MRP : null,
                        above ? mrp : null,
                        null));
                continue;
            }
            outcomes.add(shelfOutcome(line, kind, onDate, locations, lowestInStock, scope));
        }
        return outcomes;
    }

    /** A RETAIL or ADVISORY line against the control price and, for RETAIL, the lowest in-stock MRP. */
    private Outcome shelfOutcome(
            SetLines.Line line,
            String kind,
            LocalDate onDate,
            List<UUID> locations,
            Map<UUID, Optional<ShelfBatches.Shelved>> lowestInStock,
            ScopeContext scope) {
        Optional<ControlPriceView> control = controlPrices.ceilingFor(line.skuId(), line.uomCode(), onDate);
        Optional<ShelfBatches.Shelved> mrp = PriceListStore.RETAIL.equals(kind)
                ? lowestInStock.computeIfAbsent(
                        line.skuId(), sku -> ShelfBatches.lowestMrp(shelf.inStock(locations, sku, scope)))
                : Optional.empty();

        // The binding ceiling is the lower of the two; on a tie the control price is named (the law).
        String kindOfCeiling = null;
        BigDecimal ceiling = null;
        String ref = null;
        if (control.isPresent()) {
            kindOfCeiling = CEILING_CONTROL;
            ceiling = control.get().ceilingPrice();
            ref = control.get().gazetteReference();
        }
        if (mrp.isPresent() && (ceiling == null || mrp.get().printedMrp().compareTo(ceiling) < 0)) {
            kindOfCeiling = CEILING_MRP;
            ceiling = mrp.get().printedMrp();
            ref = mrp.get().batchNo();
        }
        String reason = null;
        if (ceiling != null && line.price().compareTo(ceiling) > 0) {
            reason = CEILING_CONTROL.equals(kindOfCeiling)
                    ? "m3.price_list.line.above_control_price"
                    : "m3.price_list.line.above_shelf_mrp";
        }
        return new Outcome(
                line.skuId(),
                line.uomCode(),
                line.tierFromQty(),
                reason == null,
                reason,
                null,
                kindOfCeiling,
                ceiling,
                ref);
    }

    private String refusal(
            SetLines.Line line,
            boolean shelfList,
            boolean retail,
            Map<UUID, Optional<SkuView>> skus,
            Map<String, String> tierFaults,
            Map<String, Long> occurrences,
            ScopeContext scope) {
        if (line.skuId() == null || line.uomCode() == null || line.tierFromQty() == null || line.price() == null) {
            return "m3.price_list.line.incomplete";
        }
        if (line.price().signum() < 0) {
            return "m3.price_list.line.price_negative";
        }
        // No free goods through a price (CR-23A-1, TWK D-5): a shelf price of 0.00 sells the item
        // for nothing outside the write-off controls. The slice refuses it too, but a job or a
        // till sets lines without HTTP, so the guard stays.
        if (retail && line.price().signum() == 0) {
            return "m3.price_list.line.price_zero";
        }
        if (line.price().stripTrailingZeros().scale() > (shelfList ? 2 : 4)) {
            return shelfList ? "m3.price_list.line.retail_precision" : "m3.price_list.line.price_precision";
        }
        if (line.tierFromQty().signum() < 0
                || line.tierFromQty().stripTrailingZeros().scale() > 3) {
            return "m3.price_list.line.tier_invalid";
        }
        if (shelfList && line.tierFromQty().signum() != 0) {
            return "m3.price_list.line.tier_not_allowed";
        }
        Optional<SkuView> sku = skus.computeIfAbsent(line.skuId(), id -> catalogue.getSku(id, scope));
        if (sku.isEmpty() || !ACTIVE.contains(sku.get().status())) {
            return "m3.price_list.line.sku_not_active";
        }
        if (!sku.get().baseUomCode().equals(line.uomCode())) {
            return "m3.price_list.line.uom_invalid";
        }
        if (occurrences.getOrDefault(key(line), 0L) > 1) {
            return "m3.price_list.line.duplicate";
        }
        return tierFaults.get(line.skuId() + "/" + line.uomCode());
    }

    /** doc 23 flow 6.2: the tiers of one SKU and unit start at 0 and rise ("descending tiers refused"). */
    private static Map<String, String> tierFaults(List<SetLines.Line> lines) {
        Map<String, List<Quantity>> tiers = new HashMap<>();
        for (SetLines.Line line : lines) {
            if (line.skuId() == null || line.uomCode() == null || line.tierFromQty() == null) {
                continue;
            }
            Quantity tier;
            try {
                tier = Quantity.of(line.tierFromQty());
            } catch (ArithmeticException tooPrecise) {
                continue; // refused on its own line as tier_invalid
            }
            tiers.computeIfAbsent(line.skuId() + "/" + line.uomCode(), key -> new ArrayList<>())
                    .add(tier);
        }
        Map<String, String> faults = new HashMap<>();
        tiers.forEach((key, given) -> {
            String fault = TradePriceResolver.tierFault(given);
            if (fault != null) {
                faults.put(key, fault);
            }
        });
        return faults;
    }

    private Optional<BigDecimal> lowestPrintedMrp(UUID skuId, ScopeContext scope) {
        return batches.listBatches(new BatchFilter(skuId, null, null, BATCHES_FOR_REVIEW), scope).stream()
                .map(BatchView::printedMrp)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo);
    }

    private static String key(SetLines.Line line) {
        return line.skuId() + "/" + line.uomCode() + "/"
                + line.tierFromQty().stripTrailingZeros().toPlainString();
    }
}
