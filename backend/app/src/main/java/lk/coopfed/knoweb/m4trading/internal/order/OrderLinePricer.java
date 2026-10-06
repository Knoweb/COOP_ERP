package lk.coopfed.knoweb.m4trading.internal.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.TradePricing;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.stereotype.Component;

/**
 * The line guards of an order and its lines priced at the indicative trade price of the
 * relationship: shared by CreateOrder and AmendOrder, which write what this returns. Reads only.
 * Guards per line, in order: at least one line; an item the buyer can see that is tradable; its
 * base unit (M2 publishes no conversion query yet); a positive quantity.
 */
@Component
class OrderLinePricer {

    /** The kernel's lines of the draft and the summary its events carry. */
    record Priced(List<DocumentLineRecord> lines, List<OrderLineSummary> summary) {}

    private final CatalogueQueries catalogue;
    private final TradePricing pricing;

    OrderLinePricer(CatalogueQueries catalogue, TradePricing pricing) {
        this.catalogue = catalogue;
        this.pricing = pricing;
    }

    Priced price(
            UUID orderId,
            List<CreateOrder.Line> wanted,
            RelationshipView relationship,
            UUID buyer,
            LocalDate today,
            ScopeContext scope) {
        if (wanted == null || wanted.isEmpty()) {
            throw new ProblemException("m4.order.lines_required");
        }
        List<DocumentLineRecord> lines = new ArrayList<>();
        List<OrderLineSummary> summary = new ArrayList<>();
        int lineNo = 0;
        for (CreateOrder.Line line : wanted) {
            UUID skuId = TradingGuards.required(line == null ? null : line.skuId(), "skuId");
            SkuView sku = catalogue
                    .getSku(skuId, scope)
                    .orElseThrow(() -> new ProblemException("m4.order.sku_not_found", Map.of("skuId", skuId)));
            if (!TradingGuards.tradable(sku)) {
                throw new ProblemException("m4.order.sku_not_active", Map.of("skuId", skuId));
            }
            String uom = line.uomCode() == null
                    ? sku.baseUomCode()
                    : line.uomCode().strip().toUpperCase();
            if (!uom.equals(sku.baseUomCode())) {
                throw new ProblemException("m4.order.uom_invalid", Map.of("skuId", skuId, "uomCode", uom));
            }
            TradingGuards.requirePositive(line.qty(), "m4.order.qty_not_positive", skuId);

            BigDecimal price = pricing.resolve(
                            relationship.relationshipId(),
                            relationship.sellerEntityId(),
                            buyer,
                            skuId,
                            uom,
                            line.qty(),
                            today,
                            scope)
                    .map(TradePricing.TradePrice::unitPrice)
                    .orElse(null);
            BigDecimal total = price == null ? null : price.multiply(line.qty()).setScale(2, RoundingMode.HALF_UP);
            UUID lineId = Ids.next();
            lineNo++;
            lines.add(TradingDocuments.line(
                    lineId, orderId, lineNo, skuId, null, uom, line.qty(), price, null, null, total, null, null));
            summary.add(new OrderLineSummary(lineId, lineNo, skuId, uom, line.qty(), null, null));
        }
        return new Priced(List.copyOf(lines), List.copyOf(summary));
    }
}
