package lk.coopfed.knoweb.m3pricing.internal.queries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.engine.BatchCandidate;
import lk.coopfed.knoweb.engine.Ceiling;
import lk.coopfed.knoweb.engine.EngineVersionKt;
import lk.coopfed.knoweb.engine.LineInput;
import lk.coopfed.knoweb.engine.LineResult;
import lk.coopfed.knoweb.engine.Money;
import lk.coopfed.knoweb.engine.PriceResolver;
import lk.coopfed.knoweb.engine.PricingSnapshotIndex;
import lk.coopfed.knoweb.engine.Quantity;
import lk.coopfed.knoweb.engine.RetailLine;
import lk.coopfed.knoweb.engine.SkuFacts;
import lk.coopfed.knoweb.engine.StackingPolicy;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m2catalogue.query.TaxRateView;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.ControlPriceStore;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.ShelfBatches;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListStore;
import lk.coopfed.knoweb.m3pricing.internal.policy.EffectivePolicy;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import lk.coopfed.knoweb.m3pricing.query.MrpPolicyView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import lk.coopfed.knoweb.m3pricing.query.RetailPrice;
import org.springframework.stereotype.Component;

/**
 * ResolveRetailPrice at central (23A section 6, "Central use: PricingQueriesImpl ... loads the same
 * inputs from tables into the same index type and calls the same function"). The inputs are those
 * the till's snapshot will carry (23A section 7.2; M3-09): the society's RETAIL lines, the batches
 * on the shop's shelves with their printed MRP, the effective MRP policy, and the control price.
 * The engine then takes the lowest of list, batch term and control term (doc 23 section 3.5).
 */
@Component
class RetailPriceResolution {

    private final PartyQueries party;
    private final CatalogueQueries catalogue;
    private final PriceListStore lists;
    private final ControlPriceStore controlPrices;
    private final ShelfBatches shelf;
    private final EffectivePolicy policies;

    RetailPriceResolution(
            PartyQueries party,
            CatalogueQueries catalogue,
            PriceListStore lists,
            ControlPriceStore controlPrices,
            ShelfBatches shelf,
            EffectivePolicy policies) {
        this.party = party;
        this.catalogue = catalogue;
        this.lists = lists;
        this.controlPrices = controlPrices;
        this.shelf = shelf;
        this.policies = policies;
    }

    Optional<RetailPrice> resolve(
            UUID locationId, UUID skuId, String uomCode, BigDecimal quantity, LocalDate date, ScopeContext scope) {
        Optional<LocationView> location = party.getLocation(locationId, scope);
        Optional<SkuView> sku = catalogue.getSku(skuId, scope);
        if (location.isEmpty() || sku.isEmpty() || uomCode == null || quantity == null || date == null) {
            return Optional.empty();
        }
        Quantity qty;
        try {
            qty = Quantity.of(quantity);
        } catch (ArithmeticException moreThanThreeDecimals) {
            return Optional.empty();
        }
        UUID owner = location.get().ownerEntityId();

        // 1. the society's RETAIL line in force on the date (one list per MPCS; its newest version in force)
        Optional<PriceListView> version =
                lists.rootOfKind("RETAIL", owner).flatMap(root -> lists.versionInForce(root, date));
        List<RetailLine> retailLines = version.map(v -> lists.lines(v.priceListId()).stream()
                        .filter(line ->
                                line.skuId().equals(skuId) && line.tierFromQty().signum() == 0)
                        .map(line -> new RetailLine(
                                line.skuId(),
                                line.uomCode(),
                                Money.rounded(line.price()),
                                line.effectiveFrom(),
                                line.effectiveTo(),
                                Quantity.ZERO))
                        .toList())
                .orElse(List.of());

        // 2. the batches on this shop's shelves, and the policy that picks among their MRPs
        List<BatchCandidate> candidates = shelf.inStock(List.of(locationId), skuId, scope).stream()
                .map(batch -> new BatchCandidate(
                        batch.batchId(),
                        batch.batchNo(),
                        batch.printedMrp() == null ? null : Money.rounded(batch.printedMrp()),
                        batch.expiry(),
                        Quantity.of(batch.onHand())))
                .toList();
        MrpPolicyView policy = policies.of(skuId, owner, scope);

        // 3. the control price in force, in the line's unit
        Optional<ControlPriceView> control = controlPrices.ceilingFor(skuId, uomCode, date);
        Map<UUID, List<Ceiling>> ceilings = control.map(c -> Map.of(
                        skuId,
                        List.of(new Ceiling(
                                Money.rounded(c.ceilingPrice()),
                                c.ceilingUomCode(),
                                c.gazetteReference(),
                                c.effectiveFrom(),
                                c.effectiveTo()))))
                .orElse(Map.of());

        BigDecimal taxRate = catalogue
                .taxRateInForce(skuId, date, scope)
                .map(TaxRateView::ratePercent)
                .orElse(BigDecimal.ZERO);
        PricingSnapshotIndex index = new PricingSnapshotIndex(
                0L,
                StackingPolicy.PRIORITY_THEN_BEST,
                List.of(new SkuFacts(skuId, sku.get().hasPrintedMrp(), taxRate, Set.of())),
                retailLines,
                Map.of(skuId, candidates),
                Map.of(skuId, EffectivePolicy.toEngine(policy)),
                ceilings,
                Map.of(),
                List.of());

        LineResult result = PriceResolver.resolveLine(new LineInput(skuId, uomCode, qty, null, null), index, date);
        BigDecimal listPrice = retailLines.stream()
                .filter(line -> line.getUom().equals(uomCode) && line.inForce(date))
                .findFirst()
                .map(line -> line.getPrice().getAmount())
                .orElse(null);
        return Optional.of(new RetailPrice(
                locationId,
                skuId,
                uomCode,
                result.getSellable(),
                result.getReason(),
                version.map(PriceListView::priceListId).orElse(null),
                listPrice,
                result.getSellable() ? result.getUnitPrice().getAmount() : null,
                result.getMrpApplied() == null ? null : result.getMrpApplied().getAmount(),
                control.map(ControlPriceView::ceilingPrice).orElse(null),
                result.getCapReason().name(),
                policy.policy(),
                result.getBatch() == null ? null : result.getBatch().getBatchId(),
                EngineVersionKt.ENGINE_VERSION));
    }
}
