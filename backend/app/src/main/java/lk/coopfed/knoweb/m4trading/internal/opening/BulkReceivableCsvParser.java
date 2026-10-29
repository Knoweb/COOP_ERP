package lk.coopfed.knoweb.m4trading.internal.opening;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m4trading.api.ReceivableOpeningRow;

/**
 * RFC 4180-compliant CSV parser for inter-entity receivable opening balance spreadsheets (CAT-12).
 */
public final class BulkReceivableCsvParser {

    private static final DateTimeFormatter[] DATE_FORMATS = new DateTimeFormatter[] {
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy")
    };

    private BulkReceivableCsvParser() {}

    public static List<ReceivableOpeningRow> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }

        List<List<String>> records = parseRecords(csv);
        if (records.isEmpty()) {
            return List.of();
        }

        List<String> header = records.get(0);
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            colIndex.put(normaliseHeader(header.get(i)), i);
        }

        List<ReceivableOpeningRow> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> record = records.get(r);
            if (isRecordEmpty(record)) {
                continue;
            }

            String sellerStr =
                    get(record, colIndex, "seller", "seller_code", "seller_id", "distributor", "distributor_code");
            String buyerStr = get(
                    record,
                    colIndex,
                    "buyer",
                    "buyer_code",
                    "buyer_id",
                    "mpcs",
                    "mpcs_code",
                    "customer",
                    "customer_code");
            String amountStr = get(
                    record,
                    colIndex,
                    "amount",
                    "balance",
                    "open_balance",
                    "opening_balance",
                    "receivable",
                    "receivable_amount",
                    "value");
            String dueStr = get(record, colIndex, "due_date", "due", "payment_due", "payment_due_date");
            String dateStr = get(record, colIndex, "date", "as_of_date", "tax_point_date", "invoice_date");
            String refStr = get(
                    record,
                    colIndex,
                    "reference_no",
                    "ref_no",
                    "reference",
                    "ref",
                    "reference_number",
                    "doc_no",
                    "invoice_no",
                    "legacy_no");
            String sellerSignerStr = get(
                    record, colIndex, "seller_signed_by", "seller_signer", "distributor_signer", "seller_signature");
            String buyerSignerStr =
                    get(record, colIndex, "buyer_signed_by", "buyer_signer", "mpcs_signer", "buyer_signature");
            String notesStr = get(record, colIndex, "notes", "description", "remarks", "comment");

            if (buyerStr.isEmpty() && amountStr.isEmpty()) {
                continue;
            }

            UUID sellerId = parseUuid(sellerStr);
            UUID buyerId = parseUuid(buyerStr);
            UUID sellerSignerId = parseUuid(sellerSignerStr);
            UUID buyerSignerId = parseUuid(buyerSignerStr);

            BigDecimal amount = parseBigDecimal(amountStr);
            LocalDate dueDate = parseLocalDate(dueStr);
            LocalDate asOfDate = parseLocalDate(dateStr);

            rows.add(new ReceivableOpeningRow(
                    sellerId,
                    sellerStr.isEmpty() ? null : sellerStr,
                    buyerId,
                    buyerStr.isEmpty() ? null : buyerStr,
                    amount,
                    dueDate,
                    asOfDate,
                    refStr.isEmpty() ? null : refStr,
                    sellerSignerId,
                    sellerSignerStr.isEmpty() ? null : sellerSignerStr,
                    buyerSignerId,
                    buyerSignerStr.isEmpty() ? null : buyerSignerStr,
                    notesStr.isEmpty() ? null : notesStr));
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

    private static UUID parseUuid(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static LocalDate parseLocalDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(clean, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static List<List<String>> parseRecords(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> currentRecord = new ArrayList<>();
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
                } else if (c == ',') {
                    currentRecord.add(field.toString());
                    field.setLength(0);
                } else if (c == '\r') {
                    if (i + 1 < len && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRecord.add(field.toString());
                    field.setLength(0);
                    records.add(currentRecord);
                    currentRecord = new ArrayList<>();
                } else if (c == '\n') {
                    currentRecord.add(field.toString());
                    field.setLength(0);
                    records.add(currentRecord);
                    currentRecord = new ArrayList<>();
                } else {
                    field.append(c);
                }
            }
        }

        if (field.length() > 0 || !currentRecord.isEmpty()) {
            currentRecord.add(field.toString());
            records.add(currentRecord);
        }

        return records;
    }
}
