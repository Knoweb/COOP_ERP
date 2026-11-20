package lk.coopfed.knoweb.m7customers.internal.customer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m7customers.api.BulkSetCustomerCreditLimits.CustomerLimitRow;

/**
 * Parses CSV and tabular text into {@link CustomerLimitRow} instances (CAT-14).
 */
public final class BulkCustomerCreditLimitCsvParser {

    private BulkCustomerCreditLimitCsvParser() {}

    public static List<CustomerLimitRow> parse(String csvText) {
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

        List<CustomerLimitRow> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> record = records.get(r);
            if (isRecordEmpty(record)) {
                continue;
            }

            String accountNo = get(record, colIndex, "account_no", "account", "acc_no", "account_number");
            String phone = get(record, colIndex, "phone", "phone_number", "mobile", "mobile_number", "contact");
            String nic = get(record, colIndex, "nic", "nic_number", "national_id", "identity_card");
            String idStr = get(record, colIndex, "account_id", "id");
            String limitStr = get(record, colIndex, "credit_limit", "limit", "amount", "new_limit");
            String reason = get(record, colIndex, "reason", "notes", "description");

            UUID accountId = null;
            if (!idStr.isEmpty()) {
                try {
                    accountId = UUID.fromString(idStr);
                } catch (IllegalArgumentException ignored) {
                    // Handled during validation
                }
            }

            BigDecimal creditLimit = parseBigDecimal(limitStr);

            if (!accountNo.isEmpty()
                    || !phone.isEmpty()
                    || !nic.isEmpty()
                    || accountId != null
                    || creditLimit != null) {
                rows.add(new CustomerLimitRow(
                        accountNo.isEmpty() ? null : accountNo,
                        phone.isEmpty() ? null : phone,
                        nic.isEmpty() ? null : nic,
                        accountId,
                        creditLimit,
                        reason.isEmpty() ? null : reason));
            }
        }

        return List.copyOf(rows);
    }

    private static String normaliseHeader(String name) {
        if (name == null) {
            return "";
        }
        return name.strip()
                .toLowerCase(Locale.ROOT)
                .replace(" ", "_")
                .replace("-", "_")
                .replace(".", "_");
    }

    private static String get(List<String> record, Map<String, Integer> colIndex, String... aliases) {
        for (String alias : aliases) {
            Integer idx = colIndex.get(alias);
            if (idx != null && idx < record.size()) {
                String val = record.get(idx);
                if (val != null && !val.isBlank()) {
                    return val.strip();
                }
            }
        }
        return "";
    }

    private static boolean isRecordEmpty(List<String> record) {
        for (String field : record) {
            if (field != null && !field.isBlank()) {
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
            return new BigDecimal(text.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        String s = text.startsWith("﻿") ? text.substring(1) : text;
        int len = s.length();

        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);

            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && s.charAt(i + 1) == '"') {
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
                    if (i + 1 < len && s.charAt(i + 1) == '\n') {
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
