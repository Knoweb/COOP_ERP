package lk.coopfed.knoweb.m1party.api;

import java.util.List;

/**
 * Loads users in bulk from a spreadsheet or payload with identity-provider provisioning (CAT-09).
 */
public record BulkImportUsers(String sheet, List<UserRow> users) {

    public BulkImportUsers {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public static BulkImportUsers ofSheet(String sheet) {
        return new BulkImportUsers(sheet, List.of());
    }

    public static BulkImportUsers ofUsers(List<UserRow> users) {
        return new BulkImportUsers(null, users);
    }

    public record UserRow(
            String username,
            String displayName,
            String language,
            String userKind,
            String pin,
            String password,
            String locationCode) {}
}
