package lk.coopfed.knoweb.m4trading.internal.payment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceDisputes;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceSettlements;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which invoices a sum of money settles, and by how much: the buyer's open, undisputed invoices
 * oldest first, or the ones the accounts chose, each checked against what is still due on it.
 * Shared by RecordPaymentReceipt (a new payment) and ApplyPaymentReceipt (money already on
 * account). Reads only; it locks each invoice it plans to settle, so two payments of one invoice
 * in flight at once see each other. The handlers write the allocation rows.
 */
@Component
class SettlementPlanner {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final InvoiceSettlements settlements;
    private final InvoiceDisputes disputes;

    SettlementPlanner(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            InvoiceSettlements settlements,
            InvoiceDisputes disputes) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.settlements = settlements;
        this.disputes = disputes;
    }

    /** The buyer's open, undisputed invoices of this seller, oldest first, each settled as far as the amount goes. */
    List<RecordPaymentReceipt.Settlement> oldestFirst(UUID seller, UUID buyer, BigDecimal amount) {
        List<RecordPaymentReceipt.Settlement> applied = new ArrayList<>();
        BigDecimal left = amount;
        for (UUID invoiceId : jdbc.queryForList(
                """
                select document_id from trading.doc_invoice
                 where seller_entity_id = ? and buyer_entity_id = ?
                 order by tax_point_date, document_id
                """,
                UUID.class,
                seller,
                buyer)) {
            if (left.signum() <= 0) {
                break;
            }
            DocumentRecord invoice = documents.findById(invoiceId).orElse(null);
            if (invoice == null || !invoice.isIssued() || disputes.isDisputed(invoiceId)) {
                continue;
            }
            documents.lockForLinking(invoiceId);
            BigDecimal due = settlements.amountDue(invoiceId);
            if (due.signum() <= 0) {
                continue;
            }
            BigDecimal take = due.min(left);
            applied.add(new RecordPaymentReceipt.Settlement(invoiceId, take));
            left = left.subtract(take);
        }
        return applied;
    }

    /**
     * The settlements the accounts chose, each checked against its invoice; together no more than
     * {@code amount}, else {@code exceedsCode}.
     */
    List<RecordPaymentReceipt.Settlement> chosen(
            List<RecordPaymentReceipt.Settlement> wanted,
            UUID seller,
            UUID buyer,
            BigDecimal amount,
            String exceedsCode) {
        Set<UUID> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (RecordPaymentReceipt.Settlement settlement : wanted) {
            if (settlement == null || settlement.invoiceId() == null || !seen.add(settlement.invoiceId())) {
                throw new ProblemException("m4.payment.invoice_invalid");
            }
            UUID invoiceId = settlement.invoiceId();
            DocumentRecord invoice = documents
                    .findById(invoiceId)
                    .filter(document -> "INV".equals(document.docTypeCode()))
                    .orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
            if (!seller.equals(invoice.ownerEntityId()) || !buyer.equals(invoice.counterpartyEntityId())) {
                throw new ProblemException("m4.payment.invoice_not_ours", Map.of("invoiceId", invoiceId));
            }
            if (!invoice.isIssued()) {
                throw new ProblemException("m4.invoice.not_issued");
            }
            if (settlement.amount() == null
                    || settlement.amount().signum() <= 0
                    || settlement.amount().scale() > 2) {
                throw new ProblemException("m4.payment.amount_invalid");
            }
            documents.lockForLinking(invoiceId);
            BigDecimal due = settlements.amountDue(invoiceId);
            if (settlement.amount().compareTo(due) > 0) {
                throw new ProblemException(
                        "m4.payment.exceeds_due", Map.of("invoiceId", invoiceId, "due", due.toPlainString()));
            }
            sum = sum.add(settlement.amount());
        }
        if (sum.compareTo(amount) > 0) {
            throw new ProblemException(exceedsCode);
        }
        return List.copyOf(wanted);
    }
}
