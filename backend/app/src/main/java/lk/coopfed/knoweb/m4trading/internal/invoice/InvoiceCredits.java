package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A billed unit is credited once, whoever credits it (wave 2, M4MONEY-01, 02, 05; decision (1) of
 * {@code docs/progress/deviations/2026-10-06-wave2-credit-once-per-line.md}, CR-24A-3 item 1).
 * The three credit paths (IssueCreditNote, SettleDiscrepancy, ApproveClaim) read here what earlier
 * credit notes already credited of each invoice line, and credit no more than the remainder.
 *
 * <p>The credited quantity, net and tax of an invoice line are sums over the lines of the issued
 * credit notes whose {@code reference_line_id} is that invoice line, never stored (AGENTS.md:
 * balances are sums). The read is filtered on the CN type: a debit note will reference the same
 * lines. A credit note's lines name the invoice it was raised against, whether its money was
 * applied there or not (B-1), so what an invoice line has been credited does not depend on where
 * the money went.
 *
 * <p>The caller locks the invoice ({@code documents.lockForLinking}) before it reads the lines and
 * the credits, so two credit notes of one invoice in flight at once see each other. Read only
 * (only a command handler writes).
 */
@Component
public class InvoiceCredits {

    private final JdbcTemplate jdbc;

    InvoiceCredits(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What issued credit notes credited of each line of the invoice, by invoice line id; absent means nothing. */
    public Map<UUID, Credited> creditedByLine(UUID invoiceId) {
        Map<UUID, Credited> credited = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select cl.reference_line_id, sum(cl.qty) as qty, coalesce(sum(cl.line_total), 0) as net,
                       coalesce(sum(cl.tax_amount), 0) as tax
                  from kernel.document_line cl
                  join kernel.document cn on cn.document_id = cl.document_id
                  join kernel.document_line il on il.document_line_id = cl.reference_line_id
                 where il.document_id = ? and cn.doc_type_code = 'CN' and cn.issued_at is not null
                 group by cl.reference_line_id
                """,
                invoiceId)) {
            credited.put(
                    (UUID) row.get("reference_line_id"),
                    new Credited(
                            (BigDecimal) row.get("qty"), (BigDecimal) row.get("net"), (BigDecimal) row.get("tax")));
        }
        return credited;
    }

    /** What is left to credit of a billed line, after what was credited of it. */
    public static Credited remaining(DocumentLineRecord billed, Credited credited) {
        Credited done = credited == null ? Credited.NONE : credited;
        return new Credited(
                billed.qty().subtract(done.qty()),
                orZero(billed.lineTotal()).subtract(done.net()),
                orZero(billed.taxAmount()).subtract(done.tax()));
    }

    /**
     * A credit note line for {@code qty} of an invoice line, at its price and VAT rate: net to the
     * cent, VAT per line to the cent, as the invoice builds its own. When {@code qty} is all that
     * is left of the line, the line credits exactly the net and tax still uncredited (the last
     * slice takes the remainder, M4MONEY-05), so slices rounded one by one never credit more than
     * the line billed; any other slice is capped at what is left. The caller has checked that
     * {@code qty} is above zero and no more than {@code left.qty()}.
     */
    public static DocumentLineRecord priced(
            UUID creditNoteId, int lineNo, DocumentLineRecord billed, Credited left, BigDecimal qty) {
        BigDecimal net;
        BigDecimal tax;
        if (qty.compareTo(left.qty()) == 0) {
            net = left.net().max(BigDecimal.ZERO);
            tax = left.tax().max(BigDecimal.ZERO);
        } else {
            net = billed.unitPrice().multiply(qty).setScale(2, RoundingMode.HALF_UP);
            tax = net.multiply(billed.taxRatePercent()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            net = net.min(left.net()).max(BigDecimal.ZERO);
            tax = tax.min(left.tax()).max(BigDecimal.ZERO);
        }
        return TradingDocuments.line(
                Ids.next(),
                creditNoteId,
                lineNo,
                billed.skuId(),
                billed.batchId(),
                billed.uomCode(),
                qty,
                billed.unitPrice(),
                billed.taxRatePercent(),
                tax,
                net,
                null,
                billed.id());
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** A quantity with its net and tax: what was credited of a line, or what is left of it. */
    public record Credited(BigDecimal qty, BigDecimal net, BigDecimal tax) {

        public static final Credited NONE = new Credited(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
