package lk.coopfed.knoweb.m3pricing.internal.queries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.engine.Quantity;
import lk.coopfed.knoweb.engine.TradeLine;
import lk.coopfed.knoweb.engine.TradePriceResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListStore;
import lk.coopfed.knoweb.m3pricing.internal.rule.RuleStore;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import lk.coopfed.knoweb.m3pricing.query.TradePrice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PricingQueries at central (23A section 4, queries/): loads the inputs from the tables and calls
 * the shared engine, so central and the till choose a price the same way. The scope parameter
 * looks unused but is what puts the caller's scope on the transaction for row-level security.
 */
@Service
@Transactional(readOnly = true)
class PricingQueriesImpl implements PricingQueries {

    private final PriceListStore store;
    private final RelationshipQueries relationships;
    private final RuleStore rules;

    PricingQueriesImpl(PriceListStore store, RelationshipQueries relationships, RuleStore rules) {
        this.store = store;
        this.relationships = relationships;
        this.rules = rules;
    }

    @Override
    public Optional<RuleView> getRule(UUID ruleId, ScopeContext scope) {
        return rules.find(ruleId);
    }

    @Override
    public List<RuleView> listRules(String status, String kind, ScopeContext scope) {
        return rules.list(status, kind);
    }

    @Override
    public Optional<PriceListView> getPriceList(UUID priceListId, ScopeContext scope) {
        return store.find(priceListId);
    }

    @Override
    public List<PriceListView> listPriceLists(String kind, String status, ScopeContext scope) {
        return store.list(kind, status);
    }

    @Override
    public List<PriceListLineView> lines(UUID priceListId, ScopeContext scope) {
        return store.lines(priceListId);
    }

    @Override
    public Optional<TradePrice> resolveTradePrice(
            UUID relationshipId, UUID skuId, String uomCode, BigDecimal quantity, LocalDate date, ScopeContext scope) {
        return relationships
                .getRelationship(relationshipId, scope)
                .flatMap(relationship -> priceUnder(relationship, skuId, uomCode, quantity, date));
    }

    @Override
    public Optional<TradePrice> resolveTradePrice(
            UUID sellerEntityId,
            UUID buyerEntityId,
            UUID skuId,
            String uomCode,
            BigDecimal quantity,
            LocalDate date,
            ScopeContext scope) {
        return relationships
                .lookupRelationship(sellerEntityId, buyerEntityId, date, scope)
                .flatMap(relationship -> priceUnder(relationship, skuId, uomCode, quantity, date));
    }

    private Optional<TradePrice> priceUnder(
            RelationshipView relationship, UUID skuId, String uomCode, BigDecimal quantity, LocalDate date) {
        if (relationship.priceListId() == null || skuId == null || uomCode == null || quantity == null) {
            return Optional.empty();
        }
        // The relationship binds a list by the id of any of its versions (normally version 1);
        // its root finds the version in force on the date.
        Optional<PriceListView> version = store.find(relationship.priceListId())
                .filter(bound -> PriceListStore.isTrade(bound.kind()))
                .flatMap(bound -> store.versionInForce(bound.rootPriceListId(), date));
        if (version.isEmpty()) {
            return Optional.empty();
        }
        List<TradeLine> lines = store.lines(version.get().priceListId()).stream()
                .map(line -> new TradeLine(
                        line.lineId(),
                        line.skuId(),
                        line.uomCode(),
                        Quantity.of(line.tierFromQty()),
                        line.price(),
                        line.effectiveFrom(),
                        line.effectiveTo()))
                .toList();
        Quantity ordered;
        try {
            ordered = Quantity.of(quantity);
        } catch (ArithmeticException moreThanThreeDecimals) {
            return Optional.empty();
        }
        return Optional.ofNullable(TradePriceResolver.resolve(lines, skuId, uomCode, ordered, date))
                .map(quote -> new TradePrice(
                        relationship.relationshipId(),
                        version.get().priceListId(),
                        quote.getLineId(),
                        quote.getSkuId(),
                        quote.getUom(),
                        quote.getTierFromQty().getValue(),
                        quote.getUnitPrice(),
                        quote.getEngineVersion()));
    }
}
