package lk.coopfed.knoweb.m9integration.internal.journal;

import java.math.BigDecimal;
import java.util.List;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalLineView;

/**
 * The journal file as written before m9integration V0006 (format version 1; 29A section 6.2,
 * ExportWriter; the format CSV of {@code journal_export.format}): RFC 4180, UTF-8, one header
 * line, then for every journal line its two entries, the debit and the credit, so that the file
 * reads as a double-entry journal that any package imports and that balances line by line.
 *
 * <pre>
 * entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id
 * 1,2026-09-14,INV,M042-INV-0007712,GOODS,SELLER,RECEIVABLE,24850.00,0.00,…
 * 1,2026-09-14,INV,M042-INV-0007712,GOODS,SELLER,REVENUE,0.00,24850.00,…
 * </pre>
 *
 * <p><b>FROZEN. This class never changes.</b> An export made before V0006 has no stored file: its
 * download is rebuilt by this writer from its lines, and its recorded hash is the hash of what
 * this writer gives. An edit here, however small (the header, the escaping, a rounding), would make
 * every one of those exports fail its reconciliation and hand the accountant a file that is not the
 * one the package imported (wave 2, M9-05; {@code
 * docs/progress/deviations/2026-10-06-wave2-journal-export.md} (1)). A change to the format is a
 * new writer with the next version number ({@link JournalFileV2}), and the exports made with it
 * store their bytes.
 *
 * <p>Account roles, never account numbers: the chart of accounts is mapped when the accounting
 * system is chosen (doc 10 J-02). The same lines always give the same bytes.
 */
public final class JournalFileV1 {

    static final String HEADER = "entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id";

    private static final String ZERO = "0.00";

    private JournalFileV1() {}

    public static String csv(List<JournalLineView> lines) {
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (JournalLineView line : lines) {
            row(csv, line, line.debitRole(), money(line.amount()), ZERO);
            row(csv, line, line.creditRole(), ZERO, money(line.amount()));
        }
        return csv.toString();
    }

    private static void row(StringBuilder csv, JournalLineView line, String role, String debit, String credit) {
        csv.append(line.seq())
                .append(',')
                .append(line.businessDate())
                .append(',')
                .append(text(line.docTypeCode()))
                .append(',')
                .append(text(line.docNumberDisplay()))
                .append(',')
                .append(text(line.lineKind()))
                .append(',')
                .append(text(line.side()))
                .append(',')
                .append(text(role))
                .append(',')
                .append(debit)
                .append(',')
                .append(credit)
                .append(',')
                .append(line.documentId())
                .append("\r\n");
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * A text cell: a leading apostrophe where a spreadsheet would read a formula (=, +, - or @),
     * so opening the file never runs anything; quoted where it holds a comma, a quote or a break.
     */
    static String text(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.indexOf(',') < 0 && safe.indexOf('"') < 0 && safe.indexOf('\n') < 0 && safe.indexOf('\r') < 0) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
