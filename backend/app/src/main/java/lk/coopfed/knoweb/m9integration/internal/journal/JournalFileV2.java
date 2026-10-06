package lk.coopfed.knoweb.m9integration.internal.journal;

import java.math.BigDecimal;
import java.util.List;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalLineView;

/**
 * The journal file, format version 2 (wave 2, CR-29-1; {@code
 * docs/progress/deviations/2026-10-06-wave2-journal-export.md} (1) and (2)): version 1's
 * double-entry CSV with one more column, {@code export}, on every entry, {@code FINAL} for an
 * export of a closed period and {@code PROVISIONAL} for one the accountant asked for before the
 * period closed. The mark is on every row rather than in a comment line because a CSV import has
 * no comments: an accountant sees it in the package, and a filter on the column finds the
 * provisional entries later.
 *
 * <pre>
 * entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id,export
 * 1,2026-09-14,INV,M042-INV-0007712,GOODS,SELLER,RECEIVABLE,24850.00,0.00,…,FINAL
 * 1,2026-09-14,INV,M042-INV-0007712,GOODS,SELLER,REVENUE,0.00,24850.00,…,FINAL
 * </pre>
 *
 * <p>The bytes this writer gives are stored with the export ({@code journal_export_file}) and
 * served from there; this class only has to agree with them for the reconciliation's
 * regeneration. Its helpers are its own, not {@link JournalFileV1}'s, so that neither writer can
 * change the other. When the format changes again, the next version is a new class and this one
 * is frozen like version 1.
 */
public final class JournalFileV2 {

    public static final String HEADER =
            "entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id,export";

    public static final String FINAL = "FINAL";
    public static final String PROVISIONAL = "PROVISIONAL";

    private static final String ZERO = "0.00";

    private JournalFileV2() {}

    public static String csv(List<JournalLineView> lines, boolean provisional) {
        String mark = provisional ? PROVISIONAL : FINAL;
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (JournalLineView line : lines) {
            row(csv, line, line.debitRole(), money(line.amount()), ZERO, mark);
            row(csv, line, line.creditRole(), ZERO, money(line.amount()), mark);
        }
        return csv.toString();
    }

    private static void row(
            StringBuilder csv, JournalLineView line, String role, String debit, String credit, String mark) {
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
                .append(',')
                .append(mark)
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
