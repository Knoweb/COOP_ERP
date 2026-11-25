package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Command to import members/customers in bulk from a sheet or list of member rows (CAT-10).
 *
 * @param sheet raw CSV or tabular spreadsheet text
 * @param members structured member rows
 * @param confirmedReuse whether the officer confirmed identity reuse for all members in the batch
 */
public record BulkImportMembers(String sheet, List<MemberRow> members, boolean confirmedReuse) {

    public BulkImportMembers {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public static BulkImportMembers ofSheet(String sheet) {
        return new BulkImportMembers(sheet, List.of(), false);
    }

    public static BulkImportMembers ofSheet(String sheet, boolean confirmedReuse) {
        return new BulkImportMembers(sheet, List.of(), confirmedReuse);
    }

    public static BulkImportMembers ofMembers(List<MemberRow> members) {
        return new BulkImportMembers(null, members, false);
    }

    public static BulkImportMembers ofMembers(List<MemberRow> members, boolean confirmedReuse) {
        return new BulkImportMembers(null, members, confirmedReuse);
    }

    public record MemberRow(
            String displayName,
            String displayNameSi,
            String displayNameTa,
            String language,
            String phone,
            String nic,
            BigDecimal creditLimit,
            Integer termsDays,
            BigDecimal offlineCap,
            List<String> tags,
            List<String> consents,
            boolean confirmedIdentity) {

        public MemberRow {
            tags = tags == null ? List.of() : List.copyOf(tags);
            consents = consents == null ? List.of() : List.copyOf(consents);
        }
    }
}
