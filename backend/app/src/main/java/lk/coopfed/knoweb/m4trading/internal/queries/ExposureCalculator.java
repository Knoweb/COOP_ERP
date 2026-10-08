package lk.coopfed.knoweb.m4trading.internal.queries;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceSettlements;
import lk.coopfed.knoweb.m4trading.query.ExposureView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The exposure formula of 24A section 6.3, computed on read for one seller and buyer, under the
 * caller's row-level security (both parties read every row it sums):
 *
 * <pre>
 *   openInvoices        = sum(gross - credited - settled) of the seller's issued invoices to the buyer
 *   acceptedNotInvoiced = per line of the seller's ACCEPTED orders from the buyer, what will still be
 *                         billed (undispatched, on its way, or received and not invoiced) x tier price
 *   unappliedReceipts   = sum(amount - settled) of the seller's receipts from the buyer, not reversed
 *   unappliedCredits    = sum(gross - Σ CREDITS links) of the seller's credit notes to the buyer
 *   amount              = openInvoices + acceptedNotInvoiced - unappliedReceipts - unappliedCredits
 * </pre>
 *
 * <p>The accepted part is counted per order line (wave 2, M4MONEY-14), so an order delivered on two
 * notes keeps the second note's goods in the exposure after the first is invoiced, and a short
 * quantity is not owed (DR-2). The tier price is net of VAT, as 24A's formula has it. Nothing is stored (the
 * relationship_exposure cache of 24A is not built; see the module README), so there is no stale
 * figure and no consumer to replay.
 */
@Component
public class ExposureCalculator {

    static final String THRESHOLDS = "trading.exposure_warn_thresholds";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final ConfigRegistry config;
    private final ObjectMapper json;
    private final Clock clock;
    private final InvoiceSettlements settlements;

    ExposureCalculator(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            ConfigRegistry config,
            ObjectMapper json,
            Clock clock,
            InvoiceSettlements settlements) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.config = config;
        this.json = json;
        this.clock = clock;
        this.settlements = settlements;
    }

    /** The exposure of the relationship's pair now, with its limit and the highest threshold reached. */
    public ExposureView exposure(RelationshipView relationship, ScopeContext scope) {
        UUID seller = relationship.sellerEntityId();
        UUID buyer = relationship.buyerEntityId();
        BigDecimal open = openInvoices(seller, buyer);
        BigDecimal accepted = acceptedNotInvoiced(seller, buyer);
        BigDecimal unapplied = unappliedReceipts(seller, buyer);
        BigDecimal credits = unappliedCredits(seller, buyer);
        BigDecimal amount = open.add(accepted).subtract(unapplied).subtract(credits);
        return new ExposureView(
                relationship.relationshipId(),
                seller,
                buyer,
                relationship.creditLimit(),
                open,
                accepted,
                unapplied,
                credits,
                amount,
                thresholdReached(amount, relationship.creditLimit(), scope),
                clock.instant());
    }

    /** The highest configured percentage of the limit the amount has reached; null with no limit or none reached. */
    public Integer thresholdReached(BigDecimal amount, BigDecimal limit, ScopeContext scope) {
        if (limit == null || limit.signum() <= 0) {
            return null;
        }
        Integer reached = null;
        for (int percent : thresholds(scope)) {
            BigDecimal line = limit.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100));
            if (amount.compareTo(line) >= 0 && (reached == null || percent > reached)) {
                reached = percent;
            }
        }
        return reached;
    }

    /** trading.exposure_warn_thresholds (default [80, 100]). */
    int[] thresholds(ScopeContext scope) {
        String value = config.getOrDefault(THRESHOLDS, scope, "[80, 100]");
        try {
            return Arrays.stream(json.readValue(value, int[].class)).sorted().toArray();
        } catch (JsonProcessingException e) {
            return new int[] {80, 100};
        }
    }

    BigDecimal openInvoices(UUID seller, UUID buyer) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select document_id, credited_amount, debited_amount, settled_amount from trading.doc_invoice
                 where seller_entity_id = ? and buyer_entity_id = ?
                """,
                seller,
                buyer)) {
            DocumentRecord invoice =
                    documents.findById((UUID) row.get("document_id")).orElse(null);
            if (invoice == null || !invoice.isIssued() || invoice.grossAmount() == null) {
                continue;
            }
            BigDecimal due = InvoiceSettlements.amountDue(
                    invoice.grossAmount(),
                    (BigDecimal) row.get("credited_amount"),
                    (BigDecimal) row.get("debited_amount"),
                    (BigDecimal) row.get("settled_amount"));
            if (due.signum() > 0) {
                sum = sum.add(due);
            }
        }
        return sum;
    }

    /**
     * What the buyer's accepted orders will still be billed, per order line (wave 2, CR-24A-3 item
     * 6, M4MONEY-14), at the line's tier price: the allocated quantity not yet dispatched (less what
     * the buyer cancelled), plus per delivery line of an issued note either its dispatched quantity
     * while no confirmed GRN names its drop (goods on their way will be billed), or else its share
     * of what the GRN received and no invoice has billed yet. All floored at 0: a short quantity is
     * never owed (DR-2), an invoiced one is in {@link #openInvoices}, and a cancelled order owes
     * nothing.
     */
    BigDecimal acceptedNotInvoiced(UUID seller, UUID buyer) {
        Map<UUID, OrderLine> lines = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select oa.order_id, oal.order_line_id, coalesce(oal.tier_price, 0) as tier_price,
                       greatest(least(oal.allocated_qty, ol.requested_qty - ol.cancelled_qty) - oal.fulfilled_qty, 0)
                           as undispatched
                  from trading.order_allocation oa
                  join trading.order_allocation_line oal on oal.order_id = oa.order_id
                  join trading.doc_order_line ol on ol.line_id = oal.order_line_id
                 where oa.owner_entity_id = ? and oa.counterparty_entity_id = ? and oa.status = 'ACCEPTED'
                """,
                seller,
                buyer)) {
            lines.put(
                    (UUID) row.get("order_line_id"),
                    new OrderLine((UUID) row.get("order_id"), (BigDecimal) row.get("tier_price"), (BigDecimal)
                            row.get("undispatched")));
        }
        // A cancelled order owes nothing, whatever the seller allocated or dispatched to it.
        Set<UUID> open = new HashSet<>();
        for (UUID orderId :
                lines.values().stream().map(OrderLine::orderId).distinct().toList()) {
            boolean submitted = documents
                    .findById(orderId)
                    .map(order -> OrderStatus.SUBMITTED.equals(order.status()))
                    .orElse(false);
            if (submitted) {
                open.add(orderId);
            }
        }
        if (open.isEmpty()) {
            return BigDecimal.ZERO;
        }

        Map<UUID, BigDecimal> uninvoiced = new HashMap<>();
        lines.forEach((lineId, line) -> uninvoiced.put(lineId, line.undispatched()));
        for (List<DeliveryLine> sameItemOnADrop : deliveryLines(open)) {
            DeliveryLine first = sameItemOnADrop.get(0);
            Optional<BigDecimal> received = receivedNotInvoiced(first.dropId(), first.skuId());
            if (received.isEmpty()) {
                // No confirmed GRN names the drop: on its way, and billed when it is received.
                sameItemOnADrop.forEach(
                        line -> uninvoiced.merge(line.orderLineId(), line.dispatchedQty(), BigDecimal::add));
                continue;
            }
            // A GRN line counts an item of a drop once, whichever order lines it came from; its
            // uninvoiced quantity is shared out over them in line order, the last taking the rest.
            BigDecimal remaining = received.get();
            for (int i = 0; i < sameItemOnADrop.size(); i++) {
                DeliveryLine line = sameItemOnADrop.get(i);
                BigDecimal share = i == sameItemOnADrop.size() - 1
                        ? remaining
                        : line.dispatchedQty().min(remaining);
                remaining = remaining.subtract(share);
                uninvoiced.merge(line.orderLineId(), share, BigDecimal::add);
            }
        }

        Map<UUID, BigDecimal> byOrder = new LinkedHashMap<>();
        lines.forEach((lineId, line) -> {
            if (open.contains(line.orderId())) {
                byOrder.merge(line.orderId(), uninvoiced.get(lineId).multiply(line.tierPrice()), BigDecimal::add);
            }
        });
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : byOrder.values()) {
            sum = sum.add(value.setScale(2, RoundingMode.HALF_UP));
        }
        return sum;
    }

    /** The delivery lines of issued notes for these orders, grouped by drop and item, each group in line order. */
    private List<List<DeliveryLine>> deliveryLines(Set<UUID> orderIds) {
        Map<String, List<DeliveryLine>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select dl.line_id, dl.order_line_id, dl.drop_id, dl.dispatched_qty, kl.sku_id
                  from trading.doc_delivery_line dl
                  join kernel.document dn on dn.document_id = dl.document_id and dn.issued_at is not null
                  join kernel.document_line kl on kl.document_line_id = dl.line_id
                 where dl.order_id = any (?::uuid[])
                 order by dl.drop_id, kl.sku_id, dl.line_id
                """,
                (Object) orderIds.toArray(new UUID[0]))) {
            DeliveryLine line = new DeliveryLine(
                    (UUID) row.get("order_line_id"), (UUID) row.get("drop_id"), (UUID) row.get("sku_id"), (BigDecimal)
                            row.get("dispatched_qty"));
            groups.computeIfAbsent(line.dropId() + "|" + line.skuId(), key -> new ArrayList<>())
                    .add(line);
        }
        return List.copyOf(groups.values());
    }

    /**
     * What the confirmed GRN of a drop received of an item and no issued invoice line (referencing
     * its GRN line) has billed yet, floored at 0; empty when no confirmed GRN names the drop.
     */
    private Optional<BigDecimal> receivedNotInvoiced(UUID dropId, UUID skuId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select coalesce(sum(gx.received_qty) filter (where kl.sku_id = ?), 0) as received,
                       coalesce(sum((select coalesce(sum(il.qty), 0)
                                       from kernel.document_line il
                                       join kernel.document i on i.document_id = il.document_id
                                      where il.reference_line_id = kl.document_line_id
                                        and i.doc_type_code = 'INV' and i.issued_at is not null))
                                filter (where kl.sku_id = ?), 0) as invoiced
                  from trading.doc_grn g
                  join kernel.document gd on gd.document_id = g.document_id and gd.issued_at is not null
                  join trading.doc_grn_line gx on gx.document_id = g.document_id
                  join kernel.document_line kl on kl.document_line_id = gx.line_id
                 where g.drop_id = ? and g.reversal_of is null
                having count(*) > 0
                """,
                skuId,
                skuId,
                dropId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal received = (BigDecimal) rows.get(0).get("received");
        BigDecimal invoiced = (BigDecimal) rows.get(0).get("invoiced");
        return Optional.of(received.subtract(invoiced).max(BigDecimal.ZERO));
    }

    private record OrderLine(UUID orderId, BigDecimal tierPrice, BigDecimal undispatched) {}

    private record DeliveryLine(UUID orderLineId, UUID dropId, UUID skuId, BigDecimal dispatchedQty) {}

    BigDecimal unappliedReceipts(UUID seller, UUID buyer) {
        BigDecimal sum = jdbc.queryForObject(
                """
                select coalesce(sum(r.amount - coalesce(
                           (select sum(a.amount) from trading.payment_allocation a
                             where a.receipt_document_id = r.document_id), 0)), 0)
                  from trading.doc_payment_receipt r
                 where r.seller_entity_id = ? and r.payer_entity_id = ? and r.reversal_of is null
                   and not exists (select 1 from trading.doc_payment_receipt x where x.reversal_of = r.document_id)
                """,
                BigDecimal.class,
                seller,
                buyer);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * 24A's {@code unappliedCredits}: what the seller's credit notes to the buyer hold beyond their
     * CREDITS links (CR-24A-3 item 2), a sum, never stored. A credit note's origin invoice names
     * the pair.
     */
    BigDecimal unappliedCredits(UUID seller, UUID buyer) {
        BigDecimal sum = BigDecimal.ZERO;
        for (UUID creditNoteId : jdbc.queryForList(
                """
                select c.document_id from trading.doc_credit_note c
                  join trading.doc_invoice i on i.document_id = c.invoice_document_id
                 where i.seller_entity_id = ? and i.buyer_entity_id = ?
                """,
                UUID.class,
                seller,
                buyer)) {
            BigDecimal unapplied = settlements.unappliedOf(creditNoteId);
            if (unapplied.signum() > 0) {
                sum = sum.add(unapplied);
            }
        }
        return sum;
    }
}
