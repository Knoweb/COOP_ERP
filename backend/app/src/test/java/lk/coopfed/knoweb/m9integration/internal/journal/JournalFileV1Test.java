package lk.coopfed.knoweb.m9integration.internal.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalLineView;
import org.junit.jupiter.api.Test;

/**
 * The version-1 journal file, frozen: two entries per line that balance, safe text cells, the same
 * bytes every time. These assertions pin the bytes an export made before m9integration V0006 is
 * rebuilt from; a change here means a past export's hash no longer reconciles.
 */
class JournalFileV1Test {

    private static final UUID DOCUMENT = UUID.fromString("0190e9d0-0000-7000-8000-000000000001");

    private static JournalLineView line(int seq, String number, String debit, String credit, String amount) {
        return new JournalLineView(
                seq,
                DOCUMENT,
                "INV",
                number,
                "GOODS",
                "SELLER",
                debit,
                credit,
                new BigDecimal(amount),
                LocalDate.of(2026, 8, 5),
                null);
    }

    @Test
    void everyLineIsItsDebitAndItsCreditEntry() {
        String csv = JournalFileV1.csv(List.of(line(1, "D101-INV-1", "RECEIVABLE", "REVENUE", "24850.00")));
        assertThat(csv)
                .isEqualTo(JournalFileV1.HEADER + "\r\n"
                        + "1,2026-08-05,INV,D101-INV-1,GOODS,SELLER,RECEIVABLE,24850.00,0.00," + DOCUMENT + "\r\n"
                        + "1,2026-08-05,INV,D101-INV-1,GOODS,SELLER,REVENUE,0.00,24850.00," + DOCUMENT + "\r\n");
    }

    @Test
    void aCellASpreadsheetWouldRunOrSplitIsMadeSafe() {
        assertThat(JournalFileV1.text("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(JournalFileV1.text("a,b")).isEqualTo("\"a,b\"");
        assertThat(JournalFileV1.text("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(JournalFileV1.text(null)).isEmpty();
    }

    @Test
    void theSameLinesGiveTheSameHash() {
        List<JournalLineView> lines = List.of(line(1, "X", "A", "B", "1.00"), line(2, "Y", "B", "A", "2.50"));
        assertThat(JournalFiles.sha256(JournalFileV1.csv(lines)))
                .hasSize(64)
                .isEqualTo(JournalFiles.sha256(JournalFileV1.csv(List.copyOf(lines))))
                .isNotEqualTo(JournalFiles.sha256(JournalFileV1.csv(lines.subList(0, 1))));
    }
}
