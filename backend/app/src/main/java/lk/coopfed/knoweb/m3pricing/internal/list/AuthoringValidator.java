package lk.coopfed.knoweb.m3pricing.internal.list;

import java.math.BigDecimal;
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
import org.springframework.stereotype.Component;

/**
 * The authoring checks of a TRADE list's lines (23A section 7, SetLines and PublishPriceList:
 * "per line: sku active, uom valid (M2), tiers ascending, price >= 0; AuthoringValidator ...
 * TRADE: review-only above lowest MRP"). One outcome per line, in the order given.
 *
 * <p>What it does not check yet, deferred for the demo: the control-price ceiling (M3-06, no
 * control prices exist yet), and units other than the SKU's base unit (M2 publishes no query of a
 * SKU's conversions; a trade line is priced in the base unit until it does).
 */
@Component
class AuthoringValidator {

    static final String REVIEW_ABOVE_MRP = "m3.price_list.review.above_mrp";

    /** The SKU states in which it can be traded (doc 22 section 4: LOCAL and SHARED are active). */
    private static final Set<String> ACTIVE = Set.of("LOCAL", "SHARED");

    /** The newest batches of a SKU, which are those on the market: the largest page M2 serves (BatchFilter). */
    private static final int BATCHES_FOR_REVIEW = 200;

    private final CatalogueQueries catalogue;
    private final BatchQueries batches;

    AuthoringValidator(CatalogueQueries catalogue, BatchQueries batches) {
        this.catalogue = catalogue;
        this.batches = batches;
    }

    List<Outcome> check(List<SetLines.Line> lines, ScopeContext scope) {
        Map<UUID, Optional<SkuView>> skus = new HashMap<>();
        Map<UUID, Optional<BigDecimal>> lowestMrp = new HashMap<>();
        Map<String, String> tierFaults = tierFaults(lines);
        Map<String, Long> occurrences = lines.stream()
                .filter(line -> line.skuId() != null && line.uomCode() != null && line.tierFromQty() != null)
                .collect(Collectors.groupingBy(AuthoringValidator::key, Collectors.counting()));

        List<Outcome> outcomes = new ArrayList<>();
        for (SetLines.Line line : lines) {
            String reason = refusal(line, skus, tierFaults, occurrences, scope);
            String review = null;
            if (reason == null) {
                BigDecimal mrp = lowestMrp
                        .computeIfAbsent(line.skuId(), sku -> lowestPrintedMrp(sku, scope))
                        .orElse(null);
                if (mrp != null && line.price().compareTo(mrp) > 0) {
                    // doc 23 section 3.4 and doc 10 A-04: a review, not a block.
                    review = REVIEW_ABOVE_MRP;
                }
            }
            outcomes.add(new Outcome(line.skuId(), line.uomCode(), line.tierFromQty(), reason == null, reason, review));
        }
        return outcomes;
    }

    private String refusal(
            SetLines.Line line,
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
        if (line.price().stripTrailingZeros().scale() > 4) {
            return "m3.price_list.line.price_precision";
        }
        if (line.tierFromQty().signum() < 0
                || line.tierFromQty().stripTrailingZeros().scale() > 3) {
            return "m3.price_list.line.tier_invalid";
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
