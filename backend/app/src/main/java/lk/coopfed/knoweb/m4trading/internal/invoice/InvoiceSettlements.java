package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.LinkType;
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
     * How much of a credit note's {@code gross} is applied to the invoice it credits: what is still
     * due on the invoice, at most the gross, and zero when nothing is due (decision B-1 of {@code
     * docs/progress/deviations/2026-10-06-wave2-credit-once-per-line.md}, CR-24A-3 item 2). The
     * invoice is locked first: the kernel's CREDITS check sees only the links, not what payments
     * settled. The rest is the credit note's unapplied amount, applied later by ApplyCreditNote;
     * the caller writes the CREDITS link only when the amount returned is above zero (the kernel
     * requires a positive amount).
     */
    public BigDecimal applyUpToDue(UUID invoiceId, BigDecimal gross) {
        documents.lockForLinking(invoiceId);
        BigDecimal due = amountDue(invoiceId).max(BigDecimal.ZERO);
        return gross == null ? BigDecimal.ZERO : gross.min(due);
    }

    /** What a credit note applied to invoices: the sum of its CREDITS links. */
    public BigDecimal appliedOf(UUID creditNoteId) {
        return documents.findLinks(creditNoteId).stream()
                .filter(link -> link.linkType() == LinkType.CREDITS && creditNoteId.equals(link.fromDocumentId()))
                .map(link -> link.amount() == null ? BigDecimal.ZERO : link.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** What a credit note holds unapplied: its gross less what it applied, never stored. */
    public BigDecimal unappliedOf(UUID creditNoteId) {
        BigDecimal gross = documents
                .findById(creditNoteId)
                .map(DocumentRecord::grossAmount)
                .orElse(BigDecimal.ZERO);
        return (gross == null ? BigDecimal.ZERO : gross).subtract(appliedOf(creditNoteId));
    }

    /** The credit is no more than is still due on the invoice ({@code m4.creditnote.exceeds_due}). */
    public void requireDue(UUID invoiceId, BigDecimal amount) {
        documents.lockForLinking(invoiceId);
        BigDecimal due = amountDue(invoiceId);
        if (amount.compareTo(due) > 0) {
            throw new ProblemException(
                    "m4.creditnote.exceeds_due", Map.of("due", due.toPlainString(), "amount", amount.toPlainString()));
        }
    }

    /** Gross less credited plus debited less settled. */
    public static BigDecimal amountDue(BigDecimal gross, BigDecimal credited, BigDecimal debited, BigDecimal settled) {
        BigDecimal g = gross == null ? BigDecimal.ZERO : gross;
        BigDecimal c = credited == null ? BigDecimal.ZERO : credited;
        BigDecimal d = debited == null ? BigDecimal.ZERO : debited;
        BigDecimal s = settled == null ? BigDecimal.ZERO : settled;
        return g.subtract(c).add(d).subtract(s);
    }

    /** Gross less credited plus debited less settled; zero when the invoice is not found. */
    public BigDecimal amountDue(UUID invoiceId) {
        DocumentRecord invoice = documents.findById(invoiceId).orElse(null);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select credited_amount, debited_amount from trading.doc_invoice where document_id = ?", invoiceId);
        if (invoice == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return amountDue(
                invoice.grossAmount(),
                (BigDecimal) rows.get(0).get("credited_amount"),
                (BigDecimal) rows.get(0).get("debited_amount"),
                settled(invoiceId));
    }
}
