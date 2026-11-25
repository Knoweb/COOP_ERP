package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.OpeningStockRow;

/**
 * Robust and secure OpenXML (.xlsx) and spreadsheet parser for bulk opening stock (PGL-M2-06).
 *
 * <p>Implements streaming parsing with Zip-bomb protection, XXE disabled XML reader, sparse cell
 * index resolution, Excel 1900 date system decoding, and automatic fallback to CSV/TSV parsing.
 */
public final class BulkOpeningStockExcelParser {

    private static final int MAX_ZIP_ENTRIES = 50;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 20 * 1024 * 1024; // 20 MB
    private static final long MAX_ENTRY_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final int MAX_ROWS = 5000;

    private static final byte[] ZIP_MAGIC = new byte[] {0x50, 0x4B, 0x03, 0x04};

    private BulkOpeningStockExcelParser() {}

    /**
     * Parses raw sheet input, auto-detecting base64-encoded XLSX, binary XLSX, or plain text CSV.
     */
    public static List<OpeningStockRow> parse(String sheetOrBase64) {
        if (sheetOrBase64 == null || sheetOrBase64.isBlank()) {
            return List.of();
        }

        String trimmed = sheetOrBase64.trim();
        if (trimmed.startsWith("data:") && trimmed.contains("base64,")) {
            String b64 = trimmed.substring(trimmed.indexOf("base64,") + 7).trim();
            try {
                byte[] decoded = Base64.getDecoder().decode(b64);
                return parse(decoded);
            } catch (IllegalArgumentException ignored) {
            }
        }

        if (trimmed.startsWith("UEsDB")) { // Base64 of PK\x03\x04
            try {
                byte[] decoded = Base64.getDecoder().decode(trimmed);
                return parse(decoded);
            } catch (IllegalArgumentException ignored) {
            }
        }

        // Plain CSV text
        return BulkOpeningStockCsvParser.parse(trimmed);
    }

    /**
     * Parses binary data, detecting OpenXML (.xlsx) ZIP vs CSV bytes.
     */
    public static List<OpeningStockRow> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }

        if (isZipArchive(bytes)) {
            return parseXlsx(bytes);
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        return BulkOpeningStockCsvParser.parse(text);
    }

    private static boolean isZipArchive(byte[] bytes) {
        if (bytes.length < 4) {
            return false;
        }
        return bytes[0] == ZIP_MAGIC[0]
                && bytes[1] == ZIP_MAGIC[1]
                && bytes[2] == ZIP_MAGIC[2]
                && bytes[3] == ZIP_MAGIC[3];
    }

    private static List<OpeningStockRow> parseXlsx(byte[] zipBytes) {
        List<String> sharedStrings = new ArrayList<>();
        byte[] sheetXml = null;

        int entryCount = 0;
        long totalUncompressed = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new ProblemException(
                            "m5.opening.excel_parse_error", Map.of("reason", "Too many zip entries"));
                }

                String name = entry.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("sharedstrings.xml")) {
                    byte[] data = readEntrySafely(zis, entry);
                    totalUncompressed += data.length;
                    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw new ProblemException(
                                "m5.opening.excel_parse_error", Map.of("reason", "Decompressed size limit exceeded"));
                    }
                    sharedStrings = parseSharedStrings(data);
                } else if (name.equals("xl/worksheets/sheet1.xml")
                        || (sheetXml == null && name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) {
                    sheetXml = readEntrySafely(zis, entry);
                    totalUncompressed += sheetXml.length;
                    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw new ProblemException(
                                "m5.opening.excel_parse_error", Map.of("reason", "Decompressed size limit exceeded"));
                    }
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            throw new ProblemException("m5.opening.excel_parse_error", Map.of("reason", "Corrupted zip archive"));
        }

        if (sheetXml == null) {
            throw new ProblemException(
                    "m5.opening.excel_parse_error", Map.of("reason", "No worksheet found in spreadsheet"));
        }

        List<Map<Integer, String>> rawRows = parseWorksheet(sheetXml, sharedStrings);
        return convertToOpeningStockRows(rawRows);
    }

    private static byte[] readEntrySafely(InputStream is, ZipEntry entry) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = is.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_ENTRY_BYTES) {
                throw new ProblemException(
                        "m5.opening.excel_parse_error", Map.of("reason", "Single entry size limit exceeded"));
            }
            baos.write(buffer, 0, read);
        }
        return baos.toByteArray();
    }

    private static XMLInputFactory createSecureXmlFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        return factory;
    }

    private static List<String> parseSharedStrings(byte[] xmlData) {
        List<String> list = new ArrayList<>();
        XMLInputFactory factory = createSecureXmlFactory();

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xmlData));
            StringBuilder currentText = null;
            boolean inText = false;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("si".equals(local)) {
                        currentText = new StringBuilder();
                    } else if ("t".equals(local) && currentText != null) {
                        inText = true;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS && inText && currentText != null) {
                    currentText.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("t".equals(local)) {
                        inText = false;
                    } else if ("si".equals(local) && currentText != null) {
                        list.add(currentText.toString());
                        currentText = null;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new ProblemException("m5.opening.excel_parse_error", Map.of("reason", "Malformed sharedStrings.xml"));
        }

        return list;
    }

    private static List<Map<Integer, String>> parseWorksheet(byte[] xmlData, List<String> sharedStrings) {
        List<Map<Integer, String>> rows = new ArrayList<>();
        XMLInputFactory factory = createSecureXmlFactory();

        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xmlData));
            Map<Integer, String> currentRow = null;
            Integer currentCol = null;
            String cellType = null;
            StringBuilder valueBuilder = null;
            boolean inValue = false;
            boolean inInlineText = false;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("row".equals(local)) {
                        currentRow = new HashMap<>();
                    } else if ("c".equals(local) && currentRow != null) {
                        String r = reader.getAttributeValue(null, "r");
                        cellType = reader.getAttributeValue(null, "t");
                        currentCol = parseColumnIndex(r);
                        valueBuilder = new StringBuilder();
                    } else if ("v".equals(local) && currentRow != null) {
                        inValue = true;
                    } else if ("is".equals(local) && currentRow != null) {
                        // inline string container
                    } else if ("t".equals(local) && "inlineStr".equals(cellType)) {
                        inInlineText = true;
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    if (inValue && valueBuilder != null) {
                        valueBuilder.append(reader.getText());
                    } else if (inInlineText && valueBuilder != null) {
                        valueBuilder.append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("v".equals(local)) {
                        inValue = false;
                    } else if ("t".equals(local) && "inlineStr".equals(cellType)) {
                        inInlineText = false;
                    } else if ("c".equals(local) && currentRow != null && currentCol != null) {
                        String raw = valueBuilder == null
                                ? ""
                                : valueBuilder.toString().trim();
                        String resolved = resolveCellValue(raw, cellType, sharedStrings);
                        if (!resolved.isEmpty()) {
                            currentRow.put(currentCol, resolved);
                        }
                        currentCol = null;
                        cellType = null;
                        valueBuilder = null;
                    } else if ("row".equals(local) && currentRow != null) {
                        if (!currentRow.isEmpty()) {
                            rows.add(currentRow);
                            if (rows.size() > MAX_ROWS) {
                                throw new ProblemException(
                                        "m5.opening.excel_parse_error",
                                        Map.of("reason", "Exceeded max row limit of " + MAX_ROWS));
                            }
                        }
                        currentRow = null;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new ProblemException("m5.opening.excel_parse_error", Map.of("reason", "Malformed worksheet xml"));
        }

        return rows;
    }

    private static String resolveCellValue(String raw, String cellType, List<String> sharedStrings) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if ("s".equals(cellType)) {
            try {
                int idx = Integer.parseInt(raw);
                if (idx >= 0 && idx < sharedStrings.size()) {
                    return sharedStrings.get(idx).trim();
                }
            } catch (NumberFormatException ignored) {
            }
            return "";
        }
        if ("b".equals(cellType)) {
            return "1".equals(raw) ? "TRUE" : "FALSE";
        }
        return raw;
    }

    private static Integer parseColumnIndex(String cellRef) {
        if (cellRef == null || cellRef.isBlank()) {
            return null;
        }
        int col = 0;
        int i = 0;
        while (i < cellRef.length() && Character.isLetter(cellRef.charAt(i))) {
            char c = Character.toUpperCase(cellRef.charAt(i));
            col = col * 26 + (c - 'A' + 1);
            i++;
        }
        return col > 0 ? col - 1 : null;
    }

    private static List<OpeningStockRow> convertToOpeningStockRows(List<Map<Integer, String>> rawRows) {
        if (rawRows.isEmpty()) {
            return List.of();
        }

        Map<Integer, String> headerRow = rawRows.get(0);
        Map<String, Integer> colIndex = new HashMap<>();
        for (Map.Entry<Integer, String> entry : headerRow.entrySet()) {
            String col = normaliseHeader(entry.getValue());
            if (!col.isEmpty()) {
                colIndex.putIfAbsent(col, entry.getKey());
            }
        }

        List<OpeningStockRow> rows = new ArrayList<>();
        for (int r = 1; r < rawRows.size(); r++) {
            Map<Integer, String> row = rawRows.get(r);
            if (row.isEmpty()) {
                continue;
            }

            String skuStr = get(
                    row,
                    colIndex,
                    "sku",
                    "sku_code",
                    "item",
                    "item_code",
                    "code",
                    "barcode",
                    "gtin",
                    "sku_code_or_barcode");
            String skuIdStr = get(row, colIndex, "sku_id", "skuid");
            String batchIdStr = get(row, colIndex, "batch_id", "batchid");
            String batchNo = get(row, colIndex, "batch_no", "batch", "lot", "lot_no", "batch_number");
            String expiryStr = get(row, colIndex, "expiry_date", "expiry", "exp_date", "expiration_date");
            String mrpStr = get(row, colIndex, "printed_mrp", "mrp", "retail_price");
            String qtyStr = get(row, colIndex, "qty", "quantity", "counted_qty", "count");
            String costStr = get(row, colIndex, "unit_cost", "cost", "unit_price", "price", "landed_cost");
            String conditionStr = get(row, colIndex, "condition", "status", "state", "quality");
            String uomStr = get(row, colIndex, "uom", "unit", "base_uom");

            if (skuStr.isEmpty() && skuIdStr.isEmpty() && batchIdStr.isEmpty() && qtyStr.isEmpty()) {
                continue;
            }

            UUID skuId = parseUuid(skuIdStr.isEmpty() ? skuStr : skuIdStr);
            UUID batchId = parseUuid(batchIdStr.isEmpty() ? batchNo : batchIdStr);
            BigDecimal qty = parseBigDecimal(qtyStr);
            BigDecimal unitCost = parseBigDecimal(costStr);
            BigDecimal printedMrp = parseBigDecimal(mrpStr);
            LocalDate expiryDate = parseDateOrSerial(expiryStr);
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
                    unitCost,
                    uomStr.isEmpty() ? null : uomStr));
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

    private static String get(Map<Integer, String> row, Map<String, Integer> colIndex, String... aliases) {
        for (String alias : aliases) {
            Integer idx = colIndex.get(alias);
            if (idx != null) {
                String val = row.get(idx);
                if (val != null && !val.trim().isEmpty()) {
                    return val.trim();
                }
            }
        }
        return "";
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

    /**
     * Parses an expiry date from either an Excel 1900 date serial number or standard date strings.
     */
    public static LocalDate parseDateOrSerial(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String clean = text.trim();

        // Check if numeric serial (e.g. 46538 or 46538.0)
        try {
            double serial = Double.parseDouble(clean);
            if (serial >= 1.0 && serial <= 2958465.0) { // Excel valid range
                long daySerial = (long) serial;
                if (daySerial <= 59) { // 1900-01-01 to 1900-02-28
                    return LocalDate.of(1899, 12, 31).plusDays(daySerial);
                } else { // 1900-03-01 onwards (Lotus 1-2-3 leap year bug)
                    return LocalDate.of(1899, 12, 30).plusDays(daySerial);
                }
            }
        } catch (NumberFormatException ignored) {
        }

        // Common text date patterns
        String standard = clean.replace('/', '-');
        try {
            return LocalDate.parse(standard);
        } catch (DateTimeParseException ignored) {
        }

        for (String pattern : List.of("dd-MM-yyyy", "d-M-yyyy", "MM-dd-yyyy", "yyyyMMdd")) {
            try {
                return LocalDate.parse(standard, DateTimeFormatter.ofPattern(pattern));
            } catch (DateTimeParseException ignored) {
            }
        }

        return null;
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
}
