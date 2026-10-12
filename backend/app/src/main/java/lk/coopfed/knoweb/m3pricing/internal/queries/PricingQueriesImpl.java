package lk.coopfed.knoweb.m3pricing.internal.queries;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.engine.Basket;
import lk.coopfed.knoweb.engine.BasketResult;
import lk.coopfed.knoweb.engine.Quantity;
import lk.coopfed.knoweb.engine.TenderKind;
import lk.coopfed.knoweb.engine.TradeLine;
import lk.coopfed.knoweb.engine.TradePriceResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m3pricing.internal.ceiling.ControlPriceStore;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListStore;
import lk.coopfed.knoweb.m3pricing.internal.policy.EffectivePolicy;
import lk.coopfed.knoweb.m3pricing.internal.rule.RuleStore;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import lk.coopfed.knoweb.m3pricing.query.MrpPolicyView;
import lk.coopfed.knoweb.m3pricing.query.PriceListLineView;
import lk.coopfed.knoweb.m3pricing.query.PriceListView;
import lk.coopfed.knoweb.m3pricing.query.PricingQueries;
import lk.coopfed.knoweb.m3pricing.query.RetailPrice;
import lk.coopfed.knoweb.m3pricing.query.ReviewItem;
import lk.coopfed.knoweb.m3pricing.query.RuleView;
import lk.coopfed.knoweb.m3pricing.query.TradePrice;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final ControlPriceStore controlPrices;
    private final EffectivePolicy policies;
    private final RetailPriceResolution retail;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    PricingQueriesImpl(
            PriceListStore store,
            RelationshipQueries relationships,
            RuleStore rules,
            ControlPriceStore controlPrices,
            EffectivePolicy policies,
            RetailPriceResolution retail,
            JdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.store = store;
        this.relationships = relationships;
        this.rules = rules;
        this.controlPrices = controlPrices;
        this.policies = policies;
        this.retail = retail;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<ControlPriceView> controlPrices(UUID skuId, ScopeContext scope) {
        return controlPrices.list(skuId);
    }

    @Override
    public List<ControlPriceView> controlPricesInForce(LocalDate date, ScopeContext scope) {
        return controlPrices.inForce(date);
    }

    @Override
    public Optional<ControlPriceView> controlPriceFor(UUID skuId, String uomCode, LocalDate date, ScopeContext scope) {
        return controlPrices.ceilingFor(skuId, uomCode, date);
    }

    @Override
    public MrpPolicyView effectiveMrpPolicy(UUID skuId, ScopeContext scope) {
        return policies.of(skuId, scope.entityId(), scope);
    }

    @Override
    public List<MrpPolicyView> listMrpPolicies(ScopeContext scope) {
        return policies.rows(scope);
    }

    @Override
    public Optional<RetailPrice> resolveRetailPrice(
            UUID locationId, UUID skuId, String uomCode, BigDecimal quantity, LocalDate date, ScopeContext scope) {
        return retail.resolve(locationId, skuId, uomCode, quantity, date, scope);
    }

    @Override
    public List<PriceListLineView> advisoryLines(LocalDate date, ScopeContext scope) {
        return store.advisoryInForce(date).stream()
                .flatMap(list -> store.lines(list.priceListId()).stream())
                .toList();
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

    @Override
    public Optional<BasketResult> resolveBasket(
            UUID locationId, LocalDate date, TenderKind tender, Basket basket, ScopeContext scope) {
        return retail.resolveBasket(locationId, date, tender, basket, scope);
    }

    @Override
    public List<ReviewItem> listReviews(String kind, String status, ScopeContext scope) {
        String sql =
                "select a.audit_id, a.event_type_code, a.occurred_at, a.owner_entity_id, a.subject_id, a.after_state "
                        + "from kernel.audit_event a "
                        + "join kernel.audit_event_type t on t.event_type_code = a.event_type_code "
                        + "where t.severity = 'REVIEW' "
                        + "  and a.event_type_code in ('BATCH_MRP_BELOW_LIST', 'TRADE_ABOVE_MRP', 'LIST_ABOVE_CEILING_AT_GAZETTE') "
                        + "  and a.owner_entity_id = ?";
        List<Object> args = new ArrayList<>();
        args.add(scope.entityId());

        if (kind != null && !kind.isBlank()) {
            sql += " and a.event_type_code = ?";
            args.add(kind);
        }

        sql += " order by a.occurred_at desc limit 100";

        return jdbc.query(
                sql,
                (rs, rowNum) -> {
                    Map<String, Object> details = null;
                    String json = rs.getString("after_state");
                    if (json != null) {
                        try {
                            details = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                        } catch (Exception e) {
                        }
                    }
                    return new ReviewItem(
                            UUID.fromString(rs.getString("audit_id")),
                            rs.getString("event_type_code"),
                            "REVIEW",
                            rs.getObject("occurred_at", OffsetDateTime.class),
                            UUID.fromString(rs.getString("owner_entity_id")),
                            UUID.fromString(rs.getString("subject_id")),
                            null,
                            null,
                            details,
                            false);
                },
                args.toArray());
    }
}
