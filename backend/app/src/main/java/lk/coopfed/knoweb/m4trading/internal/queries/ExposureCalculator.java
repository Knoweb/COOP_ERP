package lk.coopfed.knoweb.m4trading.internal.queries;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
 *   acceptedNotInvoiced = sum(allocated qty (less what the buyer cancelled) x tier price) of the
 *                         seller's ACCEPTED orders from the buyer that no invoice covers yet
 *   unappliedReceipts   = sum(amount - settled) of the seller's receipts from the buyer, not reversed
 *   unappliedCredits    = sum(gross - Σ CREDITS links) of the seller's credit notes to the buyer
 *   amount              = openInvoices + acceptedNotInvoiced - unappliedReceipts - unappliedCredits
 * </pre>
 *
 * <p>An order counts as invoiced once an invoice bills a GRN of a drop that carried it: the demo
 * delivers an order on one note, so what was received is billed and a short quantity is not owed
 * (DR-2). The tier price is net of VAT, as 24A's formula has it. Nothing is stored (the
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
                select document_id, credited_amount, settled_amount from trading.doc_invoice
                 where seller_entity_id = ? and buyer_entity_id = ?
                """,
                seller,
                buyer)) {
            DocumentRecord invoice =
                    documents.findById((UUID) row.get("document_id")).orElse(null);
            if (invoice == null || !invoice.isIssued() || invoice.grossAmount() == null) {
                continue;
            }
            BigDecimal due = invoice.grossAmount()
                    .subtract((BigDecimal) row.get("credited_amount"))
                    .subtract((BigDecimal) row.get("settled_amount"));
            if (due.signum() > 0) {
                sum = sum.add(due);
            }
        }
        return sum;
    }

    BigDecimal acceptedNotInvoiced(UUID seller, UUID buyer) {
        BigDecimal sum = BigDecimal.ZERO;
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select oa.order_id,
                       sum(greatest(least(oal.allocated_qty, ol.requested_qty - ol.cancelled_qty), 0)
                           * coalesce(oal.tier_price, 0)) as value
                  from trading.order_allocation oa
                  join trading.order_allocation_line oal on oal.order_id = oa.order_id
                  join trading.doc_order_line ol on ol.line_id = oal.order_line_id
                 where oa.owner_entity_id = ? and oa.counterparty_entity_id = ? and oa.status = 'ACCEPTED'
                   and not exists (
                       select 1 from trading.doc_delivery_line dl
                         join trading.doc_grn g on g.drop_id = dl.drop_id
                         join trading.doc_invoice i on g.document_id = any (i.grn_document_ids)
                        where dl.order_id = oa.order_id)
                 group by oa.order_id
                """,
                seller,
                buyer);
        for (Map<String, Object> row : rows) {
            // A cancelled order owes nothing, whatever the seller allocated to it.
            boolean cancelled = documents
                    .findById((UUID) row.get("order_id"))
                    .map(order -> !OrderStatus.SUBMITTED.equals(order.status()))
                    .orElse(true);
            if (!cancelled) {
                sum = sum.add(((BigDecimal) row.get("value")).setScale(2, RoundingMode.HALF_UP));
            }
        }
        return sum;
    }

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
