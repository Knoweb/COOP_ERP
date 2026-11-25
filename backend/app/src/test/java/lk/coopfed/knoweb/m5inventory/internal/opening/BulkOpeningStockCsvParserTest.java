package lk.coopfed.knoweb.m5inventory.internal.opening;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpeningStockRow;
import org.junit.jupiter.api.Test;

class BulkOpeningStockCsvParserTest {

    @Test
    void parsesStandardCsvHeader() {
        String csv =
                """
                sku,qty,unit_cost,batch_no,expiry_date,printed_mrp,condition
                DHAL1,50.000,180.5000,B101,2027-12-31,220.00,GOOD
                SUGAR,10.500,120.0000,B102,2026-06-30,150.00,DAMAGED
                """;

        List<OpeningStockRow> rows = BulkOpeningStockCsvParser.parse(csv);
        assertThat(rows).hasSize(2);

        OpeningStockRow r1 = rows.get(0);
        assertThat(r1.sku()).isEqualTo("DHAL1");
        assertThat(r1.qty()).isEqualByComparingTo("50.000");
        assertThat(r1.unitCost()).isEqualByComparingTo("180.5000");
        assertThat(r1.batchNo()).isEqualTo("B101");
        assertThat(r1.expiryDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(r1.printedMrp()).isEqualByComparingTo("220.00");
        assertThat(r1.condition()).isEqualTo(LotCondition.GOOD);

        OpeningStockRow r2 = rows.get(1);
        assertThat(r2.sku()).isEqualTo("SUGAR");
        assertThat(r2.qty()).isEqualByComparingTo("10.500");
        assertThat(r2.unitCost()).isEqualByComparingTo("120.0000");
        assertThat(r2.batchNo()).isEqualTo("B102");
        assertThat(r2.expiryDate()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(r2.printedMrp()).isEqualByComparingTo("150.00");
        assertThat(r2.condition()).isEqualTo(LotCondition.DAMAGED);
    }

    @Test
    void handlesAliasesQuotesAndEmptyLines() {
        String csv =
                """
                "Item Code",Quantity,"Cost",Batch,"Exp Date",MRP
                "SKU-001",100,50.25,"BATCH-A","2028/01/15",75.00

                "SKU-002",25.5,10.00,"BATCH-B",,
                """;

        List<OpeningStockRow> rows = BulkOpeningStockCsvParser.parse(csv);
        assertThat(rows).hasSize(2);

        assertThat(rows.get(0).sku()).isEqualTo("SKU-001");
        assertThat(rows.get(0).qty()).isEqualByComparingTo("100");
        assertThat(rows.get(0).unitCost()).isEqualByComparingTo("50.25");
        assertThat(rows.get(0).batchNo()).isEqualTo("BATCH-A");
        assertThat(rows.get(0).expiryDate()).isEqualTo(LocalDate.of(2028, 1, 15));
        assertThat(rows.get(0).printedMrp()).isEqualByComparingTo("75.00");
        assertThat(rows.get(0).condition()).isEqualTo(LotCondition.GOOD);

        assertThat(rows.get(1).sku()).isEqualTo("SKU-002");
        assertThat(rows.get(1).qty()).isEqualByComparingTo("25.5");
        assertThat(rows.get(1).expiryDate()).isNull();
        assertThat(rows.get(1).printedMrp()).isNull();
    }

    @Test
    void emptyOrBlankReturnsEmptyList() {
        assertThat(BulkOpeningStockCsvParser.parse(null)).isEmpty();
        assertThat(BulkOpeningStockCsvParser.parse("")).isEmpty();
        assertThat(BulkOpeningStockCsvParser.parse("   \n  \r\n")).isEmpty();
    }
}
