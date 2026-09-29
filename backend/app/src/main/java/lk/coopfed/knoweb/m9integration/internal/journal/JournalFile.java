package lk.coopfed.knoweb.m9integration.internal.journal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalLineView;

/**
 * The journal file for the accounting package (29A section 6.2, ExportWriter; the format CSV of
 * {@code journal_export.format}): RFC 4180, UTF-8, one header line, then for every journal line
 * its two entries, the debit and the credit, so that the file reads as a double-entry journal
 * that any package imports and that balances line by line.
 *
 * <pre>
 * entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id
 * 1,2026-09-14,INV,M042-INV-0007712,GOODS,SELLER,RECEIVABLE,24850.00,0.00,…
 * 1,2026-09-14,INV,M042-INV-0007712,GOODS,SELLER,REVENUE,0.00,24850.00,…
 * </pre>
 *
 * Account roles, never account numbers: the chart of accounts is mapped when the accounting
 * system is chosen (doc 10 J-02). The same lines always give the same bytes, so the content hash
 * recorded at generation proves later that the export was not altered.
 */
public final class JournalFile {

    static final String HEADER = "entry,date,doc_type,doc_number,line_kind,side,account_role,debit,credit,document_id";

    private static final String ZERO = "0.00";

    private JournalFile() {}

    public static String csv(List<JournalLineView> lines) {
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (JournalLineView line : lines) {
            row(csv, line, line.debitRole(), money(line.amount()), ZERO);
            row(csv, line, line.creditRole(), ZERO, money(line.amount()));
        }
        return csv.toString();
    }

    /** The SHA-256 of the file's bytes, in lower-case hex (64 characters, journal_export.content_hash). */
    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
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
