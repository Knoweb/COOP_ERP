package lk.coopfed.knoweb.m1party.internal.bulk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import lk.coopfed.knoweb.m1party.api.RegisterEntity;
import lk.coopfed.knoweb.m1party.api.ValidationReport.RowProblem;
import lk.coopfed.knoweb.m1party.internal.entity.EntityRegistrar;

/**
 * The rules a row of a bulk file must pass before anything is registered (21A section 3,
 * "RowValidators"). A row that arrives over HTTP as JSON has its shape checked by the kernel
 * against the slice; a row of a CSV file has no schema, so the same shape rules are here,
 * with the values the slice gives them (the lengths and enums of {@code RegisterEntityRequest}
 * in {@code openapi/m1party.yaml}). The business rules are {@link EntityRegistrar}'s and are
 * asked through it, so a rule lives once.
 */
final class RowValidators {

    static final List<String> COLUMNS = List.of(
            "entity_code",
            "entity_type",
            "legal_name_en",
            "legal_name_si",
            "legal_name_ta",
            "registration_no",
            "vat_registration_no",
            "district",
            "default_language",
            "financial_year_start_month");

    static final Set<String> REQUIRED = Set.of("entity_code", "entity_type", "legal_name_en");

    /** The types a file may register: never the Federation, which exists once (EntityRegistrar). */
    static final Set<String> FILE_TYPES = Set.of("DISTRIBUTOR", "MPCS");

    private RowValidators() {}

    /** The header columns the file must have; a missing required column is a file problem. */
    static List<String> missingColumns(List<String> header) {
        List<String> missing = new ArrayList<>();
        for (String column : REQUIRED) {
            if (!header.contains(column)) {
                missing.add(column);
            }
        }
        return missing;
    }

    /**
     * The problems of one row: shape first (required, length, enum, number), then the code
     * against the rows before it in the file and against the register. {@code codeExists}
     * asks the register.
     */
    static List<RowProblem> problemsOf(CsvTable.Row row, Set<String> codesSeen, Predicate<String> codeExists) {
        List<RowProblem> problems = new ArrayList<>();

        String code = row.get("entity_code");
        if (code.isEmpty()) {
            problems.add(new RowProblem("entity_code", "bulk.row.value_required"));
        } else if (code.length() > EntityRegistrar.CODE_MAX_LENGTH) {
            problems.add(new RowProblem("entity_code", "bulk.row.value_too_long"));
        } else if (!codesSeen.add(code.toUpperCase(Locale.ROOT))) {
            problems.add(new RowProblem("entity_code", "bulk.row.duplicate_in_file"));
        } else if (codeExists.test(code)) {
            problems.add(new RowProblem("entity_code", "bulk.row.code_exists"));
        }

        String type = EntityRegistrar.normalizeType(row.get("entity_type"));
        if (type.isEmpty()) {
            problems.add(new RowProblem("entity_type", "bulk.row.value_required"));
        } else if (!FILE_TYPES.contains(type)) {
            problems.add(new RowProblem("entity_type", "bulk.row.value_invalid"));
        }

        if (row.get("legal_name_en").isEmpty()) {
            problems.add(new RowProblem("legal_name_en", "bulk.row.value_required"));
        }

        if (row.get("registration_no").length() > 40) {
            problems.add(new RowProblem("registration_no", "bulk.row.value_too_long"));
        }
        if (row.get("vat_registration_no").length() > 40) {
            problems.add(new RowProblem("vat_registration_no", "bulk.row.value_too_long"));
        }

        String language = row.get("default_language");
        if (!language.isEmpty() && !EntityRegistrar.VALID_LANGUAGES.contains(language.toLowerCase(Locale.ROOT))) {
            problems.add(new RowProblem("default_language", "bulk.row.value_invalid"));
        }

        String month = row.get("financial_year_start_month");
        if (!month.isEmpty()) {
            try {
                int m = Integer.parseInt(month);
                if (m < 1 || m > 12) {
                    problems.add(new RowProblem("financial_year_start_month", "bulk.row.value_invalid"));
                }
            } catch (NumberFormatException e) {
                problems.add(new RowProblem("financial_year_start_month", "bulk.row.value_invalid"));
            }
        }

        return problems;
    }

    /** The command for a row that passed {@link #problemsOf}. */
    static RegisterEntity commandOf(CsvTable.Row row) {
        String month = row.get("financial_year_start_month");
        return new RegisterEntity(
                row.get("entity_code"),
                EntityRegistrar.normalizeType(row.get("entity_type")),
                row.get("legal_name_en"),
                blankToNull(row.get("legal_name_si")),
                blankToNull(row.get("legal_name_ta")),
                blankToNull(row.get("registration_no")),
                blankToNull(row.get("vat_registration_no")),
                blankToNull(row.get("district")),
                row.get("default_language").isEmpty()
                        ? "en"
                        : row.get("default_language").toLowerCase(Locale.ROOT),
                month.isEmpty() ? 1 : Integer.parseInt(month));
    }

    private static String blankToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /** Codes are compared without case, as the register's unique index does. */
    static Set<String> codeSet() {
        return new HashSet<>();
    }
}
