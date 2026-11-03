package lk.coopfed.knoweb.m2catalogue.internal.supplier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.m2catalogue.api.SupplierImportRow;
import org.springframework.stereotype.Component;

/**
 * Fast, resilient CSV and delimited text parser for supplier migration sheets (PGL-M2-05).
 */
@Component
public class SupplierCsvParser {

    private static final String[] NAME_ALIASES = {
        "name", "suppliername", "supplier_name", "vendorname", "vendor_name", "supplier", "vendor", "company"
    };

    private static final String[] CODE_ALIASES = {
        "suppliercode", "supplier_code", "code", "legacycode", "legacy_code", "vendorcode", "vendor_code", "id"
    };

    public List<SupplierImportRow> parse(String csvContent) throws IOException {
        if (csvContent == null || csvContent.isBlank()) {
            return List.of();
        }

        List<SupplierImportRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                return List.of();
            }

            String delimiter = detectDelimiter(headerLine);
            List<String> headers = parseLine(headerLine, delimiter);
            Map<String, Integer> colIndex = new HashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                colIndex.put(normalizeHeader(headers.get(i)), i);
            }

            boolean hasNameCol = false;
            for (String alias : NAME_ALIASES) {
                if (colIndex.containsKey(normalizeHeader(alias))) {
                    hasNameCol = true;
                    break;
                }
            }
            if (!hasNameCol && !headers.isEmpty()) {
                throw new IllegalArgumentException("A supplier name column is required in the CSV header");
            }

            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = parseLine(line, delimiter);
                SupplierImportRow row = mapCellsToRow(rowNumber++, cells, colIndex);
                if (row != null) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private SupplierImportRow mapCellsToRow(int rowNumber, List<String> cells, Map<String, Integer> colIndex) {
        String name = getCell(cells, colIndex, NAME_ALIASES);
        String code = getCell(cells, colIndex, CODE_ALIASES);

        // If sheet has only 1 column without recognizable header, take first column as name
        if (name == null && !cells.isEmpty() && colIndex.isEmpty()) {
            name = cells.get(0).strip();
        }

        return new SupplierImportRow(
                rowNumber, name != null ? name.strip() : null, code != null && !code.isBlank() ? code.strip() : null);
    }

    private static String getCell(List<String> cells, Map<String, Integer> colIndex, String... aliases) {
        for (String alias : aliases) {
            Integer idx = colIndex.get(normalizeHeader(alias));
            if (idx != null && idx < cells.size()) {
                String val = cells.get(idx);
                if (val != null) {
                    return val.strip();
                }
            }
        }
        return null;
    }

    private static String detectDelimiter(String headerLine) {
        int commas = countMatches(headerLine, ',');
        int tabs = countMatches(headerLine, '\t');
        int semicolons = countMatches(headerLine, ';');

        if (tabs > commas && tabs > semicolons) return "\t";
        if (semicolons > commas) return ";";
        return ",";
    }

    private static int countMatches(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) count++;
        }
        return count;
    }

    private static String normalizeHeader(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]", "");
    }

    private static List<String> parseLine(String line, String delimiter) {
        List<String> tokens = new ArrayList<>();
        char delim = delimiter.charAt(0);
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delim && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens;
    }
}
