package lk.coopfed.knoweb.m7customers.internal.customer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.m7customers.api.BulkImportMembers;

/**
 * Parses CSV and tabular text into {@link BulkImportMembers.MemberRow} instances (CAT-10).
 */
public final class BulkMemberCsvParser {

    private BulkMemberCsvParser() {}

    public static List<BulkImportMembers.MemberRow> parse(String csvText) {
        if (csvText == null || csvText.isBlank()) {
            return List.of();
        }

        List<List<String>> records = parseCsv(csvText);
        if (records.isEmpty()) {
            return List.of();
        }

        List<String> header = records.get(0);
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String col = normaliseHeader(header.get(i));
            if (!col.isEmpty()) {
                colIndex.putIfAbsent(col, i);
            }
        }

        List<BulkImportMembers.MemberRow> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> record = records.get(r);
            if (isRecordEmpty(record)) {
                continue;
            }

            String displayName =
                    get(record, colIndex, "display_name", "name", "full_name", "member_name", "customer_name");
            String displayNameSi = get(record, colIndex, "display_name_si", "name_si", "sinhala_name");
            String displayNameTa = get(record, colIndex, "display_name_ta", "name_ta", "tamil_name");
            String language = get(record, colIndex, "language", "lang", "locale");
            String phone = get(record, colIndex, "phone", "phone_number", "mobile", "mobile_number", "contact");
            String nic = get(record, colIndex, "nic", "nic_number", "national_id", "identity_card");
            String limitStr = get(record, colIndex, "credit_limit", "limit", "initial_limit");
            String termsStr = get(record, colIndex, "terms_days", "terms", "terms_in_days");
            String capStr = get(record, colIndex, "offline_cap", "cap");
            String tagsStr = get(record, colIndex, "tags", "tag");
            String consentsStr = get(record, colIndex, "consents", "consent");
            String confirmedStr =
                    get(record, colIndex, "confirmed_identity", "confirmed_reuse", "confirmed", "confirm");

            if (displayName.isEmpty() && phone.isEmpty() && nic.isEmpty()) {
                continue;
            }

            BigDecimal creditLimit = parseBigDecimal(limitStr);
            Integer termsDays = parseInteger(termsStr);
            BigDecimal offlineCap = parseBigDecimal(capStr);

            List<String> tags = parseList(tagsStr);
            List<String> consents = parseList(consentsStr);
            if (consents.isEmpty()) {
                consents = List.of("CREDIT_ACCOUNT");
            }

            boolean confirmed = parseBoolean(confirmedStr);

            rows.add(new BulkImportMembers.MemberRow(
                    displayName.isEmpty() ? null : displayName,
                    displayNameSi.isEmpty() ? null : displayNameSi,
                    displayNameTa.isEmpty() ? null : displayNameTa,
                    language.isEmpty() ? "si" : language.toLowerCase(Locale.ROOT),
                    phone.isEmpty() ? null : phone,
                    nic.isEmpty() ? null : nic,
                    creditLimit,
                    termsDays,
                    offlineCap,
                    tags,
                    consents,
                    confirmed));
        }

        return List.copyOf(rows);
    }

    private static String normaliseHeader(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
        return clean.replace("-", "_").replace(" ", "_");
    }

    private static String get(List<String> record, Map<String, Integer> colIndex, String... aliases) {
        for (String alias : aliases) {
            Integer idx = colIndex.get(alias);
            if (idx != null && idx < record.size()) {
                String val = record.get(idx).trim();
                if (!val.isEmpty()) {
                    return val;
                }
            }
        }
        return "";
    }

    private static boolean isRecordEmpty(List<String> record) {
        for (String s : record) {
            if (s != null && !s.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal parseBigDecimal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseInteger(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean parseBoolean(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String val = text.trim().toLowerCase(Locale.ROOT);
        return "true".equals(val) || "1".equals(val) || "yes".equals(val) || "y".equals(val);
    }

    private static List<String> parseList(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',' || c == '\t') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                } else if (c == '\r') {
                    if (i + 1 < len && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                } else if (c == '\n') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                } else {
                    field.append(c);
                }
            }
        }

        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow);
        }

        return rows;
    }
}
