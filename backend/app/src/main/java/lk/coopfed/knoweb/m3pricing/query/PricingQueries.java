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

    // ---- M3-06: control prices (every authenticated scope reads them) --------------------------

    /** Every control price, of one SKU when {@code skuId} is not null: the history, newest first. */
    List<ControlPriceView> controlPrices(UUID skuId, ScopeContext scope);

    /** ControlPricesInForce(date) of doc 23 section 5.2. */
    List<ControlPriceView> controlPricesInForce(LocalDate date, ScopeContext scope);

    /** ControlPriceFor(sku, date) in the unit: the ceiling in force; empty when there is none. */
    Optional<ControlPriceView> controlPriceFor(UUID skuId, String uomCode, LocalDate date, ScopeContext scope);

    // ---- M3-07: the multi-MRP policy -----------------------------------------------------------

    /** The effective MRP policy of a SKU at the caller's entity: its own row, the Federation's, or the default. */
    MrpPolicyView effectiveMrpPolicy(UUID skuId, ScopeContext scope);

    /** The stored policies that apply at the caller's entity: its own and, where it has none, the Federation's. */
    List<MrpPolicyView> listMrpPolicies(ScopeContext scope);

    // ---- M3-06: retail prices ------------------------------------------------------------------

    /**
     * ResolveRetailPrice(location, sku, uom, qty, date) of doc 23 section 5.2: the shelf price of the
     * SKU at a shop on the date, through the shared engine (steps 1 to 4 of doc 23 section 3.5): the
     * society's published RETAIL line in force, bounded by the printed MRP of the batches in stock at
     * the location under the effective MRP policy, and by the control price. Rules are not applied.
     * Empty when the caller's scope does not see the location. The till will get the same inputs in
     * its snapshot (M3-09) and call the same function.
     */
    Optional<RetailPrice> resolveRetailPrice(
            UUID locationId, UUID skuId, String uomCode, BigDecimal quantity, LocalDate date, ScopeContext scope);

    /** The published ADVISORY lines in force on the date, of every Federation list (the shelf list's advisory column). */
    List<PriceListLineView> advisoryLines(LocalDate date, ScopeContext scope);
}
