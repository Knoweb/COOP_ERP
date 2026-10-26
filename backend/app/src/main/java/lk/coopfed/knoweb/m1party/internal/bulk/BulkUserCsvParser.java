package lk.coopfed.knoweb.m1party.internal.bulk;

import java.util.ArrayList;
import java.util.List;
import lk.coopfed.knoweb.m1party.api.BulkImportUsers;

/**
 * Parses a CSV user spreadsheet into a list of {@link BulkImportUsers.UserRow} records (CAT-09).
 */
public final class BulkUserCsvParser {

    private BulkUserCsvParser() {}

    public static List<BulkImportUsers.UserRow> parse(String csvText) {
        if (csvText == null || csvText.isBlank()) {
            return List.of();
        }

        CsvTable table = CsvTable.parse(csvText);
        if (table.rows().isEmpty()) {
            return List.of();
        }

        List<BulkImportUsers.UserRow> users = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            String username = valueOf(row, "username", "user_name");
            String displayName = valueOf(row, "display_name", "full_name", "name", "user_display_name");
            String lang = valueOf(row, "language", "lang", "default_language");
            String userKind = valueOf(row, "user_kind", "kind");
            String pin = valueOf(row, "pin", "user_pin");
            String password = valueOf(row, "password", "temp_password", "temporary_password", "initial_password");
            String locationCode = valueOf(row, "location_code", "shop_code", "location");

            if (!username.isEmpty() || !displayName.isEmpty()) {
                users.add(new BulkImportUsers.UserRow(
                        username,
                        displayName.isEmpty() ? username : displayName,
                        lang.isEmpty() ? null : lang,
                        userKind.isEmpty() ? null : userKind,
                        pin.isEmpty() ? null : pin,
                        password.isEmpty() ? null : password,
                        locationCode.isEmpty() ? null : locationCode));
            }
        }

        return List.copyOf(users);
    }

    private static String valueOf(CsvTable.Row row, String... aliases) {
        for (String alias : aliases) {
            String val = row.get(alias);
            if (!val.isEmpty()) {
                return val;
            }
        }
        return "";
    }
}
