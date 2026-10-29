package lk.coopfed.knoweb.m4trading.internal.opening;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import lk.coopfed.knoweb.m4trading.api.ReceivableOpeningRow;
import org.junit.jupiter.api.Test;

class BulkReceivableCsvParserTest {

    @Test
    void parsesStandardCsvHeader() {
        String csv =
                """
                seller,buyer,amount,due_date,reference_no,notes
                DIST1,MPCS1,150000.50,2026-11-30,INV-LEGACY-001,Prior month balance
                DIST1,MPCS2,75000.00,2026-12-15,INV-LEGACY-002,Opening stock settlement
                """;

        List<ReceivableOpeningRow> rows = BulkReceivableCsvParser.parse(csv);
        assertThat(rows).hasSize(2);

        ReceivableOpeningRow r1 = rows.get(0);
        assertThat(r1.sellerCode()).isEqualTo("DIST1");
        assertThat(r1.buyerCode()).isEqualTo("MPCS1");
        assertThat(r1.amount()).isEqualByComparingTo("150000.50");
        assertThat(r1.dueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(r1.referenceNo()).isEqualTo("INV-LEGACY-001");
        assertThat(r1.notes()).isEqualTo("Prior month balance");

        ReceivableOpeningRow r2 = rows.get(1);
        assertThat(r2.sellerCode()).isEqualTo("DIST1");
        assertThat(r2.buyerCode()).isEqualTo("MPCS2");
        assertThat(r2.amount()).isEqualByComparingTo("75000.00");
        assertThat(r2.dueDate()).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(r2.referenceNo()).isEqualTo("INV-LEGACY-002");
    }

    @Test
    void handlesAliasesQuotesAndSignatures() {
        String csv =
                """
                "Distributor Code","MPCS Code","Opening Balance","Due","Ref No","Seller Signer","Buyer Signer"
                "DIST-01","MPCS-99",250000.00,"2027/01/10","OPEN-REF-99","officer1","buyer_mgr"

                "DIST-01","MPCS-88",12500.75,"2027/02/28","OPEN-REF-88",,
                """;

        List<ReceivableOpeningRow> rows = BulkReceivableCsvParser.parse(csv);
        assertThat(rows).hasSize(2);

        assertThat(rows.get(0).sellerCode()).isEqualTo("DIST-01");
        assertThat(rows.get(0).buyerCode()).isEqualTo("MPCS-99");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("250000.00");
        assertThat(rows.get(0).dueDate()).isEqualTo(LocalDate.of(2027, 1, 10));
        assertThat(rows.get(0).referenceNo()).isEqualTo("OPEN-REF-99");
        assertThat(rows.get(0).sellerSigner()).isEqualTo("officer1");
        assertThat(rows.get(0).buyerSigner()).isEqualTo("buyer_mgr");

        assertThat(rows.get(1).sellerCode()).isEqualTo("DIST-01");
        assertThat(rows.get(1).buyerCode()).isEqualTo("MPCS-88");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("12500.75");
        assertThat(rows.get(1).dueDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(rows.get(1).sellerSigner()).isNull();
        assertThat(rows.get(1).buyerSigner()).isNull();
    }
}
