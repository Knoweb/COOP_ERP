package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The queries of doc 23 section 5.2 built so far. Row-level security decides what a caller sees:
 * the owner its lists; the buyer of a relationship the published versions of the TRADE list the
 * relationship binds; the Federation view everything.
 */
public interface PricingQueries {

    /** GetPriceList: one version; empty when it does not exist or the caller may not see it. */
    Optional<PriceListView> getPriceList(UUID priceListId, ScopeContext scope);

    /**
     * ListPriceLists: the versions visible to the caller, by name and then newest version first.
     * {@code kind} and {@code status} filter when not null.
     */
    List<PriceListView> listPriceLists(String kind, String status, ScopeContext scope);

    /** The lines of one version, by SKU, unit and tier; empty when the caller may not see it. */
    List<PriceListLineView> lines(UUID priceListId, ScopeContext scope);

    /**
     * ResolveTradePrice(relationship, sku, uom, qty, date) of doc 23 section 5.2 and 24A section 2:
     * the price of the TRADE list the relationship binds, from the newest version published on or
     * before the date, at the tier that is the highest not above the quantity (the ordered
     * quantity, doc 10 A-03). Empty when the relationship is not visible, binds no list, or the
     * list has no line for the SKU and unit.
     */
    Optional<TradePrice> resolveTradePrice(
            UUID relationshipId, UUID skuId, String uomCode, BigDecimal quantity, LocalDate date, ScopeContext scope);

    /**
     * The same for a (seller, buyer) pair: the relationship in force on the date (M1's
     * LookupRelationship), then its list. The form M4 uses to price an order line.
     */
    Optional<TradePrice> resolveTradePrice(
            UUID sellerEntityId,
            UUID buyerEntityId,
            UUID skuId,
            String uomCode,
            BigDecimal quantity,
            LocalDate date,
            ScopeContext scope);

    /** GetRule: one discount rule; empty when it does not exist or the caller may not see it. */
    Optional<RuleView> getRule(UUID ruleId, ScopeContext scope);

    /** ListRules: the rules visible to the caller, newest validity first; filters when not null. */
    List<RuleView> listRules(String status, String kind, ScopeContext scope);
}
