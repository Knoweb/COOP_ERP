package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What an invoice's payments settled, and what is still due on it (doc 24 section 9.4): the settled
 * amount is the sum of the invoice's {@code trading.payment_allocation} rows (a reversal's rows are
 * negative), never added to; the amount due is the gross less what was credited and settled.
 * Read only (only a command handler writes): the handlers that write allocations write the
 * settled_amount cache themselves, and the credit note handlers ask it so they credit no more than
 * is still due.
 */
@Component
public class InvoiceSettlements {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;

    InvoiceSettlements(JdbcTemplate jdbc, DocumentBaseRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    /** The invoice's settled amount, recomputed from its allocation rows. */
    public BigDecimal settled(UUID invoiceId) {
        BigDecimal sum = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from trading.payment_allocation where invoice_document_id = ?",
                BigDecimal.class,
                invoiceId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /**
     * A credit of {@code amount} fits in what is still due on the invoice, which is locked first:
     * the kernel's CREDITS check sees only the links, not what payments settled.
     */
    public void requireDue(UUID invoiceId, BigDecimal amount) {
        documents.lockForLinking(invoiceId);
        BigDecimal due = amountDue(invoiceId);
        if (amount.compareTo(due) > 0) {
            throw new ProblemException(
                    "m4.creditnote.exceeds_due", Map.of("due", due.toPlainString(), "amount", amount.toPlainString()));
        }
    }

    /** Gross less credited less settled; zero when the invoice is not found. */
    public BigDecimal amountDue(UUID invoiceId) {
        DocumentRecord invoice = documents.findById(invoiceId).orElse(null);
        List<Map<String, Object>> rows =
                jdbc.queryForList("select credited_amount from trading.doc_invoice where document_id = ?", invoiceId);
        if (invoice == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal gross = invoice.grossAmount() == null ? BigDecimal.ZERO : invoice.grossAmount();
        BigDecimal credited = (BigDecimal) rows.get(0).get("credited_amount");
        return gross.subtract(credited).subtract(settled(invoiceId));
    }
}
