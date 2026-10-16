package lk.coopfed.knoweb.m2catalogue.internal.steward;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.m2catalogue.api.ImportSkuRow;
import org.springframework.stereotype.Component;

/**
 * High-speed CSV and delimited text parser for catalogue SKU import sheets (CAT-01, 22A section 6.2).
 */
@Component
public class BulkImportCsvParser {

    public List<ImportSkuRow> parse(String csvContent) throws IOException {
        if (csvContent == null || csvContent.isBlank()) {
            return List.of();
        }

        List<ImportSkuRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                return List.of();
            }

            // Detect delimiter: comma, tab or semicolon
            String delimiter = detectDelimiter(headerLine);
            List<String> headers = parseLine(headerLine, delimiter);
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                colIndex.put(normalizeHeader(headers.get(i)), i);
            }

            String line;
            int rowNumber = 1; // 1-based data row (header is row 0)
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cells = parseLine(line, delimiter);
                ImportSkuRow row = mapCellsToRow(rowNumber++, cells, colIndex);
                rows.add(row);
            }
        }

        return rows;
    }

    private ImportSkuRow mapCellsToRow(int rowNumber, List<String> cells, Map<String, Integer> colIndex) {
        String code = getCell(cells, colIndex, "skucode", "code");
        String nameEn = getCell(cells, colIndex, "nameen", "name", "itemname");
        String nameSi = getCell(cells, colIndex, "namesi");
        String nameTa = getCell(cells, colIndex, "nameta");
        String descEn = getCell(cells, colIndex, "descriptionen", "description", "desc");
        String descSi = getCell(cells, colIndex, "descriptionsi");
        String descTa = getCell(cells, colIndex, "descriptionta");
        String uom = getCell(cells, colIndex, "baseuomcode", "baseuom", "uom");
        Boolean weight = getWeightBool(cells, colIndex, uom);
        Boolean batch = getBool(cells, colIndex, "batchtracked", "batch");
        Boolean expiry = getBool(cells, colIndex, "expirytracked", "expiry");
        Boolean printedMrp = getBool(cells, colIndex, "hasprintedmrp", "printedmrp");
        Short expiryWarning = getShort(cells, colIndex, "expirywarningdays", "warningdays");
        String tax = getCell(cells, colIndex, "taxcategorycode", "taxcategory", "tax");
        BigDecimal price = getDecimal(cells, colIndex, "price", "mrp");
        List<String> barcodes = getList(cells, colIndex, "barcodes", "barcode");
        String brand = getCell(cells, colIndex, "brand");
        List<String> tags = getList(cells, colIndex, "tags", "tag");

        return new ImportSkuRow(
                rowNumber,
                code,
                nameEn,
                nameSi,
                nameTa,
                descEn,
                descSi,
                descTa,
                uom,
                weight,
                batch,
                expiry,
                printedMrp,
                expiryWarning,
                tax,
                price,
                barcodes,
                brand,
                tags,
                Map.of());
    }

    private static String getCell(List<String> cells, Map<String, Integer> colIndex, String... aliases) {
        for (String alias : aliases) {
            Integer idx = colIndex.get(alias);
            if (idx != null && idx < cells.size()) {
                String val = cells.get(idx).trim();
                if (!val.isEmpty()) return val;
            }
        }
        return null;
    }

    private static Boolean getBool(List<String> cells, Map<String, Integer> colIndex, String... aliases) {
        String val = getCell(cells, colIndex, aliases);
        if (val == null) return false;
        String lower = val.toLowerCase(Locale.ROOT);
        return "true".equals(lower)
                || "yes".equals(lower)
                || "1".equals(lower)
                || "t".equals(lower)
                || "y".equals(lower);
    }

    private static Boolean getWeightBool(List<String> cells, Map<String, Integer> colIndex, String uom) {
        String val = getCell(cells, colIndex, "soldbyweight", "weight");
        if (val == null) {
            return "KG".equalsIgnoreCase(uom);
        }
        String lower = val.toLowerCase(Locale.ROOT);
        return "true".equals(lower)
                || "yes".equals(lower)
                || "1".equals(lower)
                || "t".equals(lower)
                || "y".equals(lower);
    }

    private static Short getShort(List<String> cells, Map<String, Integer> colIndex, String... aliases) {
        String val = getCell(cells, colIndex, aliases);
        if (val == null) return null;
        try {
            return Short.parseShort(val.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal getDecimal(List<String> cells, Map<String, Integer> colIndex, String... aliases) {
        String val = getCell(cells, colIndex, aliases);
        if (val == null) return null;
        try {
            return new BigDecimal(val.replaceAll("[^0-9.-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> getList(List<String> cells, Map<String, Integer> colIndex, String... aliases) {
        String val = getCell(cells, colIndex, aliases);
        if (val == null || val.isBlank()) return List.of();
        String[] split = val.split("[,;|]");
        return Arrays.stream(split).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String detectDelimiter(String line) {
        if (line.contains("\t")) return "\t";
        if (line.contains(";") && !line.contains(",")) return ";";
        return ",";
    }

    private static String normalizeHeader(String h) {
        return h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static List<String> parseLine(String line, String delimiter) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();

        char delim = delimiter.charAt(0);

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delim && !inQuotes) {
                tokens.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        tokens.add(current.toString().trim());
        return tokens;
    }
}
