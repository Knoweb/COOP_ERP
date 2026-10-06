package lk.coopfed.knoweb.m9integration.internal.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalLineView;
import org.junit.jupiter.api.Test;

/** The version-2 journal file: version 1's entries with the export mark on each, and the writers by version. */
class JournalFileV2Test {

    private static final UUID DOCUMENT = UUID.fromString("0190e9d0-0000-7000-8000-000000000002");

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
    void everyEntryCarriesTheExportMark() {
        List<JournalLineView> lines = List.of(line(1, "D101-INV-1", "RECEIVABLE", "REVENUE", "24850.00"));
        assertThat(JournalFileV2.csv(lines, false))
                .isEqualTo(JournalFileV2.HEADER + "\r\n"
                        + "1,2026-08-05,INV,D101-INV-1,GOODS,SELLER,RECEIVABLE,24850.00,0.00," + DOCUMENT + ",FINAL\r\n"
                        + "1,2026-08-05,INV,D101-INV-1,GOODS,SELLER,REVENUE,0.00,24850.00," + DOCUMENT + ",FINAL\r\n");
        assertThat(JournalFileV2.csv(lines, true)).endsWith(",PROVISIONAL\r\n").doesNotContain("FINAL");
    }

    @Test
    void theWritersByVersionGiveTheirOwnBytesAndRefuseAnUnknownVersion() {
        List<JournalLineView> lines = List.of(line(1, "X", "A", "B", "1.00"));
        assertThat(new String(JournalFiles.bytes(1, lines, true), StandardCharsets.UTF_8))
                .isEqualTo(JournalFileV1.csv(lines));
        assertThat(new String(JournalFiles.bytes(2, lines, true), StandardCharsets.UTF_8))
                .isEqualTo(JournalFileV2.csv(lines, true));
        assertThat(JournalFiles.CURRENT_VERSION).isEqualTo((short) 2);
        assertThatThrownBy(() -> JournalFiles.bytes(3, lines, false)).isInstanceOf(IllegalStateException.class);
        assertThat(JournalFiles.sha256(JournalFiles.bytes(2, lines, false)))
                .hasSize(64)
                .isEqualTo(JournalFiles.sha256(JournalFileV2.csv(lines, false)))
                .isNotEqualTo(JournalFiles.sha256(JournalFiles.bytes(2, lines, true)));
    }
}
