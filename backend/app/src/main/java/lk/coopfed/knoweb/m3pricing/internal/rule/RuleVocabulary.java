package lk.coopfed.knoweb.m3pricing.internal.rule;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.api.RuleBenefit;
import lk.coopfed.knoweb.m3pricing.api.RulePredicate;
import org.springframework.stereotype.Component;

/**
 * The closed vocabulary of doc 23 section 3.3, kind by kind (23A section 7, "RuleSchemaValidator
 * for the kind"): which predicate fields a kind needs and allows, which benefits it takes, and the
 * bounds of a benefit. 23A keeps one JSON schema per kind under seed/m3/rule-schemas; the checks
 * are written here instead, one plain method a reader can follow (deviation, module README), and
 * the answer is a message id the screen shows.
 *
 * <ul>
 *   <li>TIME_LIMITED_PRICE: an item, a unit optional; FIXED_PRICE, PERCENT_OFF or AMOUNT_OFF.
 *   <li>QUANTITY_BREAK: an item and a minimum quantity; PERCENT_OFF or FIXED_PRICE.
 *   <li>BILL_THRESHOLD: a bill total; PERCENT_OFF or AMOUNT_OFF off the bill.
 *   <li>EXPIRY_MARKDOWN: an expiry-tracked item and days to expiry; PERCENT_OFF or FIXED_PRICE.
 * </ul>
 *
 * <p>FREE_ITEM is refused as not available: the engine does not evaluate it yet (it adds a line only
 * when the free item is in stock at the till), deferred for the demo (M3-02).
 */
@Component
class RuleVocabulary {

    static final String TIME_LIMITED_PRICE = "TIME_LIMITED_PRICE";
    static final String QUANTITY_BREAK = "QUANTITY_BREAK";
    static final String BILL_THRESHOLD = "BILL_THRESHOLD";
    static final String FREE_ITEM = "FREE_ITEM";
    static final String EXPIRY_MARKDOWN = "EXPIRY_MARKDOWN";

    static final String FIXED_PRICE = "FIXED_PRICE";
    static final String PERCENT_OFF = "PERCENT_OFF";
    static final String AMOUNT_OFF = "AMOUNT_OFF";

    private static final Map<String, Set<String>> BENEFITS = Map.of(
            TIME_LIMITED_PRICE, Set.of(FIXED_PRICE, PERCENT_OFF, AMOUNT_OFF),
            QUANTITY_BREAK, Set.of(PERCENT_OFF, FIXED_PRICE),
            BILL_THRESHOLD, Set.of(PERCENT_OFF, AMOUNT_OFF),
            EXPIRY_MARKDOWN, Set.of(PERCENT_OFF, FIXED_PRICE));

    /** The SKU states in which an item is sold (doc 22 section 4). */
    private static final Set<String> ACTIVE = Set.of("LOCAL", "SHARED");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CatalogueQueries catalogue;

    RuleVocabulary(CatalogueQueries catalogue) {
        this.catalogue = catalogue;
    }

    /** Throws the first rule the predicate and benefit break, in the order of the table above. */
    void check(String kind, RulePredicate predicate, RuleBenefit benefit, ScopeContext scope) {
        if (kind == null) {
            throw new ProblemException("request.field.required", Map.of("field", "kind"));
        }
        if (FREE_ITEM.equals(kind)) {
            throw new ProblemException("m3.rule.kind_not_available", Map.of("kind", kind));
        }
        if (!BENEFITS.containsKey(kind)) {
            throw new ProblemException("m3.rule.kind_invalid", Map.of("kind", kind));
        }
        RulePredicate p = predicate == null ? new RulePredicate(null, null, null, null, null) : predicate;

        if (BILL_THRESHOLD.equals(kind)) {
            forbid(p.skuId(), "skuId");
            forbid(p.uomCode(), "uomCode");
            forbid(p.minQty(), "minQty");
            forbid(p.daysToExpiry(), "daysToExpiry");
            if (p.billTotalFrom() == null || p.billTotalFrom().signum() <= 0 || scaleOf(p.billTotalFrom()) > 2) {
                throw new ProblemException("m3.rule.bill_total_required");
            }
        } else {
            forbid(p.billTotalFrom(), "billTotalFrom");
            SkuView sku = activeSku(p, scope);
            if (TIME_LIMITED_PRICE.equals(kind)) {
                forbid(p.minQty(), "minQty");
                forbid(p.daysToExpiry(), "daysToExpiry");
                if (p.uomCode() != null && !p.uomCode().equals(sku.baseUomCode())) {
                    // Retail lines are set in the base unit so far (M3-04, no M2 conversion query).
                    throw new ProblemException("m3.rule.uom_invalid");
                }
            } else if (QUANTITY_BREAK.equals(kind)) {
                forbid(p.uomCode(), "uomCode");
                forbid(p.daysToExpiry(), "daysToExpiry");
                if (p.minQty() == null || p.minQty().signum() <= 0 || scaleOf(p.minQty()) > 3) {
                    throw new ProblemException("m3.rule.min_qty_required");
                }
            } else {
                forbid(p.uomCode(), "uomCode");
                forbid(p.minQty(), "minQty");
                if (p.daysToExpiry() == null || p.daysToExpiry() < 0) {
                    throw new ProblemException("m3.rule.days_to_expiry_required");
                }
                // doc 23 section 3.3: an expiry markdown needs an expiry-tracked item.
                if (!sku.expiryTracked()) {
                    throw new ProblemException("m3.rule.not_expiry_tracked");
                }
            }
        }
        checkBenefit(kind, benefit);
    }

    private SkuView activeSku(RulePredicate p, ScopeContext scope) {
        if (p.skuId() == null) {
            throw new ProblemException("m3.rule.sku_required");
        }
        Optional<SkuView> sku = catalogue.getSku(p.skuId(), scope);
        if (sku.isEmpty() || !ACTIVE.contains(sku.get().status())) {
            throw new ProblemException("m3.rule.sku_not_active");
        }
        return sku.get();
    }

    private static void checkBenefit(String kind, RuleBenefit benefit) {
        if (benefit == null || benefit.kind() == null || !BENEFITS.get(kind).contains(benefit.kind())) {
            throw new ProblemException(
                    "m3.rule.benefit_not_allowed",
                    Map.of("kind", kind, "benefit", benefit == null ? "" : String.valueOf(benefit.kind())));
        }
        BigDecimal value = benefit.value();
        boolean valid = value != null && scaleOf(value) <= 2;
        // No free goods through a price (CR-23A-1, D11): 100 % off or a fixed price of 0 would give
        // the item away outside the write-off controls; that is a DONATION or SAMPLES write-off.
        if (valid && PERCENT_OFF.equals(benefit.kind())) {
            // 0 would change nothing; 100 would make the item free.
            valid = value.signum() > 0 && value.compareTo(HUNDRED) < 0;
        } else if (valid) {
            valid = value.signum() > 0; // AMOUNT_OFF, and FIXED_PRICE: a price above zero
        }
        if (!valid) {
            throw new ProblemException("m3.rule.benefit_value_invalid");
        }
    }

    private static void forbid(Object value, String field) {
        if (value != null) {
            throw new ProblemException("m3.rule.field_not_allowed", Map.of("field", field));
        }
    }

    private static int scaleOf(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }
}
