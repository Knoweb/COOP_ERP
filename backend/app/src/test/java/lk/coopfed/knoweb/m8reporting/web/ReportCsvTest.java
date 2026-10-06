package lk.coopfed.knoweb.m8reporting.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.m8reporting.query.ReportTable;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;
import org.junit.jupiter.api.Test;

/**
 * The CSV's guard against a cell a spreadsheet would run (wave 2, M8-10): a TEXT cell that starts
 * with = + - @, or with a tab, carriage return or line feed, gets a leading apostrophe; a MONEY
 * cell that starts with "-" is a negative number and is written as it is.
 */
class ReportCsvTest {

    private static final Messages LABELS = (id, locale, args) -> new Messages.Text(id, false);

    @Test
    void aTextCellThatCouldStartAFormulaIsNeutralisedAndANegativeAmountIsNot() {
        ReportTable table = new ReportTable(
                "r",
                "t",
                List.of(new Column("name", "name", "TEXT"), new Column("amount", "amount", "MONEY")),
                List.of(
                        Map.of("name", "\t=HYPERLINK(\"x\")", "amount", "-12.50"),
                        Map.of("name", "\r=1+1", "amount", "3.00"),
                        Map.of("name", "\n@SUM(A1)", "amount", "0.00"),
                        Map.of("name", "=1+1", "amount", "1.00"),
                        Map.of("name", "Kuliyapitiya MPCS", "amount", "2.00")),
                Map.of(),
                null,
                Instant.parse("2026-10-06T00:00:00Z"));

        assertThat(ReportCsv.write(table, LABELS, Locale.ENGLISH))
                .startsWith("name,amount\r\n")
                .contains("\"'\t=HYPERLINK(\"\"x\"\")\",-12.50")
                .contains("\"'\r=1+1\",3.00")
                .contains("\"'\n@SUM(A1)\",0.00")
                .contains("'=1+1,1.00")
                .contains("Kuliyapitiya MPCS,2.00")
                .doesNotContain(",'-12.50");
    }
}
