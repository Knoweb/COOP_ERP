package lk.coopfed.knoweb.m2catalogue.internal.steward;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import lk.coopfed.knoweb.m2catalogue.api.ImportSkuRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BulkImportCsvParserTest {

    private BulkImportCsvParser parser;

    @BeforeEach
    void setUp() {
        parser = new BulkImportCsvParser();
    }

    @Test
    void parsesCommaDelimitedCsvWithHeaders() throws IOException {
        String csv =
                """
                sku_code,name_en,name_si,name_ta,base_uom,tax_category,price,barcodes,brand
                SKU-001,Milk Powder 400g,කිරි පිටි,பால் மா,EA,STD,1080.00,4791000000008,anchor
                SKU-002,White Sugar 1kg,,,KG,STD,320.00,,cargills
                """;

        List<ImportSkuRow> rows = parser.parse(csv);
        assertThat(rows).hasSize(2);

        ImportSkuRow row1 = rows.get(0);
        assertThat(row1.skuCode()).isEqualTo("SKU-001");
        assertThat(row1.nameEn()).isEqualTo("Milk Powder 400g");
        assertThat(row1.nameSi()).isEqualTo("කිරි පිටි");
        assertThat(row1.nameTa()).isEqualTo("பால் மா");
        assertThat(row1.baseUomCode()).isEqualTo("EA");
        assertThat(row1.taxCategoryCode()).isEqualTo("STD");
        assertThat(row1.price()).isEqualByComparingTo(new BigDecimal("1080.00"));
        assertThat(row1.barcodes()).containsExactly("4791000000008");
        assertThat(row1.brand()).isEqualTo("anchor");

        ImportSkuRow row2 = rows.get(1);
        assertThat(row2.skuCode()).isEqualTo("SKU-002");
        assertThat(row2.nameEn()).isEqualTo("White Sugar 1kg");
        assertThat(row2.baseUomCode()).isEqualTo("KG");
        assertThat(row2.soldByWeight()).isTrue();
    }

    @Test
    void handlesEmptyOrBlankCsv() throws IOException {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   \n  \n")).isEmpty();
    }

    @Test
    void handlesQuotedFieldsWithCommas() throws IOException {
        String csv =
                """
                name_en,base_uom,tax_category
                "Tea, Premium BOPF",EA,STD
                """;

        List<ImportSkuRow> rows = parser.parse(csv);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).nameEn()).isEqualTo("Tea, Premium BOPF");
    }
}
