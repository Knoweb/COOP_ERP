package lk.coopfed.knoweb.m9integration.internal.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalLineView;
import org.junit.jupiter.api.Test;

/** The journal file: two entries per line that balance, safe text cells, the same bytes every time. */
class JournalFileTest {

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
        String csv = JournalFile.csv(List.of(line(1, "D101-INV-1", "RECEIVABLE", "REVENUE", "24850.00")));
        assertThat(csv)
                .isEqualTo(JournalFile.HEADER + "\r\n"
                        + "1,2026-08-05,INV,D101-INV-1,GOODS,SELLER,RECEIVABLE,24850.00,0.00," + DOCUMENT + "\r\n"
                        + "1,2026-08-05,INV,D101-INV-1,GOODS,SELLER,REVENUE,0.00,24850.00," + DOCUMENT + "\r\n");
    }

    @Test
    void aCellASpreadsheetWouldRunOrSplitIsMadeSafe() {
        assertThat(JournalFile.text("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(JournalFile.text("a,b")).isEqualTo("\"a,b\"");
        assertThat(JournalFile.text("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(JournalFile.text(null)).isEmpty();
    }

    @Test
    void theSameLinesGiveTheSameHash() {
        List<JournalLineView> lines = List.of(line(1, "X", "A", "B", "1.00"), line(2, "Y", "B", "A", "2.50"));
        assertThat(JournalFile.sha256(JournalFile.csv(lines)))
                .hasSize(64)
                .isEqualTo(JournalFile.sha256(JournalFile.csv(List.copyOf(lines))))
                .isNotEqualTo(JournalFile.sha256(JournalFile.csv(lines.subList(0, 1))));
    }
}
