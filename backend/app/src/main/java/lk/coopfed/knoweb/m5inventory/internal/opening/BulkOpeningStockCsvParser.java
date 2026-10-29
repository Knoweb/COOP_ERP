package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpeningStockRow;

/**
 * Parses spreadsheet (CSV/TSV) opening stock files into {@link OpeningStockRow} instances (CAT-11).
 */
public final class BulkOpeningStockCsvParser {

    private BulkOpeningStockCsvParser() {}

    public static List<OpeningStockRow> parse(String csvText) {
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

        List<OpeningStockRow> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> record = records.get(r);
            if (isRecordEmpty(record)) {
                continue;
            }

            String skuStr = get(record, colIndex, "sku", "sku_code", "item", "item_code", "code", "barcode");
            String skuIdStr = get(record, colIndex, "sku_id", "skuid");
            String batchIdStr = get(record, colIndex, "batch_id", "batchid");
            String batchNo = get(record, colIndex, "batch_no", "batch", "lot", "lot_no", "batch_number");
            String expiryStr = get(record, colIndex, "expiry_date", "expiry", "exp_date", "expiration_date");
            String mrpStr = get(record, colIndex, "printed_mrp", "mrp", "retail_price");
            String qtyStr = get(record, colIndex, "qty", "quantity", "counted_qty", "count");
            String costStr = get(record, colIndex, "unit_cost", "cost", "unit_price", "price");
            String conditionStr = get(record, colIndex, "condition", "status", "state");

            if (skuStr.isEmpty() && skuIdStr.isEmpty() && batchIdStr.isEmpty() && qtyStr.isEmpty()) {
                continue;
            }

            UUID skuId = parseUuid(skuIdStr.isEmpty() ? skuStr : skuIdStr);
            UUID batchId = parseUuid(batchIdStr.isEmpty() ? batchNo : batchIdStr);
            BigDecimal qty = parseBigDecimal(qtyStr);
            BigDecimal unitCost = parseBigDecimal(costStr);
            BigDecimal printedMrp = parseBigDecimal(mrpStr);
            LocalDate expiryDate = parseLocalDate(expiryStr);
            LotCondition condition = parseCondition(conditionStr);

            rows.add(new OpeningStockRow(
                    skuStr.isEmpty() ? null : skuStr,
                    skuId,
                    batchId,
                    batchNo.isEmpty() ? null : batchNo,
                    expiryDate,
                    printedMrp,
                    condition,
                    qty,
                    unitCost));
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
        String clean = text.trim().replace('/', '-');
        try {
            return LocalDate.parse(clean);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LotCondition parseCondition(String text) {
        if (text == null || text.isBlank()) {
            return LotCondition.GOOD;
        }
        String val = text.trim().toUpperCase(Locale.ROOT);
        if ("DAMAGED".equals(val)) {
            return LotCondition.DAMAGED;
        }
        return LotCondition.GOOD;
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
