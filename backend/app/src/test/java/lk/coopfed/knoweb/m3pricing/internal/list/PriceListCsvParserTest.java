package lk.coopfed.knoweb.m3pricing.internal.list;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceListCsvParserTest {

    private final PriceListCsvParser parser = new PriceListCsvParser();

    @Test
    void parsesStandardCsvHeaderAndRows() throws IOException {
        String csv =
                """
                sku,price,uom,tier
                SKU-001,150.50,EA,0
                4790000000018,220.00,KG,5.000
                """;

        List<PriceListCsvParser.ParsedRow> lines = parser.parse(csv);
        assertThat(lines).hasSize(2);

        PriceListCsvParser.ParsedRow line1 = lines.get(0);
        assertThat(line1.rawSku()).isEqualTo("SKU-001");
        assertThat(line1.price()).isEqualByComparingTo("150.50");
        assertThat(line1.uomCode()).isEqualTo("EA");
        assertThat(line1.tierFromQty()).isEqualByComparingTo("0");

        PriceListCsvParser.ParsedRow line2 = lines.get(1);
        assertThat(line2.rawSku()).isEqualTo("4790000000018");
        assertThat(line2.price()).isEqualByComparingTo("220.00");
        assertThat(line2.uomCode()).isEqualTo("KG");
        assertThat(line2.tierFromQty()).isEqualByComparingTo("5.000");
    }

    @Test
    void handlesTsvAndSemicolonDelimiters() throws IOException {
        String tsv = "barcode\tprice\n4790000000242\t860.00\n";
        List<PriceListCsvParser.ParsedRow> lines = parser.parse(tsv);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).rawSku()).isEqualTo("4790000000242");
        assertThat(lines.get(0).price()).isEqualByComparingTo("860.00");

        String semi = "item;unit_price\nSUGAR-1KG;245.00\n";
        List<PriceListCsvParser.ParsedRow> semiLines = parser.parse(semi);
        assertThat(semiLines).hasSize(1);
        assertThat(semiLines.get(0).rawSku()).isEqualTo("SUGAR-1KG");
        assertThat(semiLines.get(0).price()).isEqualByComparingTo("245.00");
    }

    @Test
    void skipsCommentsAndEmptyLines() throws IOException {
        String csv =
                """
                # Comment line
                sku_code,price
                # Another comment

                RICE-5KG,1290.00
                """;
        List<PriceListCsvParser.ParsedRow> lines = parser.parse(csv);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).rawSku()).isEqualTo("RICE-5KG");
        assertThat(lines.get(0).price()).isEqualByComparingTo("1290.00");
    }

    @Test
    void handlesQuotedFieldsWithCommas() throws IOException {
        String csv = """
                "sku","price"
                "TEA, CEYLON",450.00
                """;
        List<PriceListCsvParser.ParsedRow> lines = parser.parse(csv);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).rawSku()).isEqualTo("TEA, CEYLON");
        assertThat(lines.get(0).price()).isEqualByComparingTo("450.00");
    }

    @Test
    void emptyOrBlankContentReturnsEmptyList() throws IOException {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   \n\n")).isEmpty();
        assertThat(parser.parse("sku,price\n")).isEmpty();
    }
}
