package lk.coopfed.knoweb.m2catalogue.internal.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Supplier CSV Parser")
class SupplierCsvParserTest {

    private final SupplierCsvParser parser = new SupplierCsvParser();

    @Test
    @DisplayName("parses comma-separated values with standard Name and SupplierCode headers")
    void parsesCommaSeparatedStandardHeaders() throws Exception {
        String csv =
                """
                Name,SupplierCode
                Ceylon Agro Foods,SUP-001
                Lanka Sugar Ltd,SUP-002
                """;

        List<SupplierImportRow> rows = parser.parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(new SupplierImportRow(1, "Ceylon Agro Foods", "SUP-001"));
        assertThat(rows.get(1)).isEqualTo(new SupplierImportRow(2, "Lanka Sugar Ltd", "SUP-002"));
    }

    @Test
    @DisplayName("parses semicolon-separated values and alternate headers like Code, Vendor, Supplier Name")
    void parsesSemicolonSeparatedAndAlternateHeaders() throws Exception {
        String csv =
                """
                Vendor;Code
                Highland Milk;VEND-HLM
                Kelani Valley;VEND-KV
                """;

        List<SupplierImportRow> rows = parser.parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(new SupplierImportRow(1, "Highland Milk", "VEND-HLM"));
        assertThat(rows.get(1)).isEqualTo(new SupplierImportRow(2, "Kelani Valley", "VEND-KV"));
    }

    @Test
    @DisplayName("handles quoted fields with commas and leading/trailing whitespace")
    void handlesQuotedFieldsAndWhitespace() throws Exception {
        String csv =
                """
                "Supplier Name", "Supplier Code"
                "Nestle Lanka, PLC", "SUP-NES"
                " Maliban Biscuit Manufactories ", "SUP-MAL"
                """;

        List<SupplierImportRow> rows = parser.parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("Nestle Lanka, PLC");
        assertThat(rows.get(0).supplierCode()).isEqualTo("SUP-NES");
        assertThat(rows.get(1).name()).isEqualTo("Maliban Biscuit Manufactories");
        assertThat(rows.get(1).supplierCode()).isEqualTo("SUP-MAL");
    }

    @Test
    @DisplayName("supports optional supplier code column when only supplier name is provided")
    void supportsOptionalCodeColumn() throws Exception {
        String csv =
                """
                Name
                Local Farmer Co-op
                Island Spices
                """;

        List<SupplierImportRow> rows = parser.parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isEqualTo(new SupplierImportRow(1, "Local Farmer Co-op", null));
        assertThat(rows.get(1)).isEqualTo(new SupplierImportRow(2, "Island Spices", null));
    }

    @Test
    @DisplayName("throws IllegalArgumentException when name column is missing from headers")
    void throwsWhenNameColumnMissing() {
        String csv =
                """
                SupplierCode,Phone,Email
                SUP-001,0771234567,supplier@test.lk
                """;

        assertThatThrownBy(() -> parser.parse(csv))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name column is required");
    }

    @Test
    @DisplayName("returns empty list for blank or empty content")
    void returnsEmptyForBlankContent() throws Exception {
        assertThat(parser.parse("")).isEmpty();
        assertThat(parser.parse("   \n   \n")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
    }
}
