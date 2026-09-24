package lk.coopfed.knoweb.m1party.internal.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import lk.coopfed.knoweb.m1party.api.ValidationReport.RowProblem;
import org.junit.jupiter.api.Test;

class RowValidatorsTest {

    private static CsvTable.Row row(Map<String, String> values) {
        return new CsvTable.Row(2, values);
    }

    private static List<RowProblem> problems(Map<String, String> values) {
        return RowValidators.problemsOf(row(values), RowValidators.codeSet(), code -> false);
    }

    @Test
    void aCompleteRowHasNoProblem() {
        assertThat(problems(Map.of(
                        "entity_code", "M001",
                        "entity_type", "mpcs",
                        "legal_name_en", "Society One",
                        "default_language", "SI",
                        "financial_year_start_month", "4")))
                .isEmpty();
    }

    @Test
    void theRequiredColumnsAreRequired() {
        assertThat(problems(Map.of("entity_code", "", "entity_type", "", "legal_name_en", "")))
                .extracting(RowProblem::field, RowProblem::code)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("entity_code", "bulk.row.value_required"),
                        org.assertj.core.groups.Tuple.tuple("entity_type", "bulk.row.value_required"),
                        org.assertj.core.groups.Tuple.tuple("legal_name_en", "bulk.row.value_required"));
    }

    @Test
    void theLengthsAndEnumsOfTheSliceApply() {
        List<RowProblem> found = problems(Map.of(
                "entity_code", "TOO-LONG-A-CODE",
                "entity_type", "FEDERATION",
                "legal_name_en", "x",
                "registration_no", "R".repeat(41),
                "default_language", "fr",
                "financial_year_start_month", "13"));

        assertThat(found)
                .extracting(RowProblem::field, RowProblem::code)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("entity_code", "bulk.row.value_too_long"),
                        org.assertj.core.groups.Tuple.tuple("entity_type", "bulk.row.value_invalid"),
                        org.assertj.core.groups.Tuple.tuple("registration_no", "bulk.row.value_too_long"),
                        org.assertj.core.groups.Tuple.tuple("default_language", "bulk.row.value_invalid"),
                        org.assertj.core.groups.Tuple.tuple("financial_year_start_month", "bulk.row.value_invalid"));
    }

    @Test
    void aCodeRepeatedInTheFileIsRefusedTheSecondTime_caseInsensitively() {
        Set<String> seen = RowValidators.codeSet();
        Map<String, String> first = Map.of("entity_code", "M001", "entity_type", "MPCS", "legal_name_en", "One");
        Map<String, String> second = Map.of("entity_code", "m001", "entity_type", "MPCS", "legal_name_en", "Two");

        assertThat(RowValidators.problemsOf(row(first), seen, code -> false)).isEmpty();
        assertThat(RowValidators.problemsOf(row(second), seen, code -> false))
                .extracting(RowProblem::code)
                .containsExactly("bulk.row.duplicate_in_file");
    }

    @Test
    void aCodeAlreadyInTheRegisterIsRefused() {
        Map<String, String> values = Map.of("entity_code", "M001", "entity_type", "MPCS", "legal_name_en", "One");

        assertThat(RowValidators.problemsOf(row(values), RowValidators.codeSet(), code -> code.equals("M001")))
                .extracting(RowProblem::code)
                .containsExactly("bulk.row.code_exists");
    }

    @Test
    void theCommandOfARowFillsTheDefaultsTheSliceGives() {
        RegisterEntity command = RowValidators.commandOf(
                row(Map.of("entity_code", "M001", "entity_type", "mpcs", "legal_name_en", "One", "district", "")));

        assertThat(command.entityType()).isEqualTo("MPCS");
        assertThat(command.defaultLanguage()).isEqualTo("en");
        assertThat(command.financialYearStartMonth()).isEqualTo(1);
        assertThat(command.district()).isNull();
    }
}
