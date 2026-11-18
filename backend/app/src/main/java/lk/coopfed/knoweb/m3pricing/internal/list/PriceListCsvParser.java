package lk.coopfed.knoweb.m3pricing.internal.list;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * High-speed CSV, TSV and delimited spreadsheet parser for bulk price list lines (CAT-13).
 */
@Component
public class PriceListCsvParser {

    public record ParsedRow(
            int lineNo,
            String rawSku,
            UUID skuId,
            String uomCode,
            BigDecimal tierFromQty,
            BigDecimal price,
            String parseError) {}

    public List<ParsedRow> parse(String content) throws IOException {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<ParsedRow> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String firstLine = null;
            while ((firstLine = reader.readLine()) != null) {
                String trimmed = firstLine.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    break;
                }
            }
            if (firstLine == null
                    || firstLine.trim().isEmpty()
                    || firstLine.trim().startsWith("#")) {
                return List.of();
            }

            String delimiter = detectDelimiter(firstLine);
            List<String> firstTokens = parseTokens(firstLine, delimiter);
            boolean hasHeader = isHeaderRow(firstTokens);

            Map<String, Integer> colIndex = new HashMap<>();
            if (hasHeader) {
                for (int i = 0; i < firstTokens.size(); i++) {
                    colIndex.put(normalize(firstTokens.get(i)), i);
                }
            } else {
                // Positional defaults: 0: sku, 1: uom, 2: tier, 3: price, or 0: sku, 1: price
                if (firstTokens.size() == 2) {
                    colIndex.put("sku", 0);
                    colIndex.put("price", 1);
                } else if (firstTokens.size() == 3) {
                    colIndex.put("sku", 0);
                    colIndex.put("uom", 1);
                    colIndex.put("price", 2);
                } else {
                    colIndex.put("sku", 0);
                    colIndex.put("uom", 1);
                    colIndex.put("tier", 2);
                    colIndex.put("price", 3);
                }
                rows.add(mapRow(1, firstTokens, colIndex));
            }

            String line;
            int lineNo = hasHeader ? 2 : 2;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                List<String> tokens = parseTokens(line, delimiter);
                rows.add(mapRow(lineNo++, tokens, colIndex));
            }
        }

        return rows;
    }

    private ParsedRow mapRow(int lineNo, List<String> cells, Map<String, Integer> colIndex) {
        String sku = getCell(cells, colIndex, "sku", "skucode", "code", "barcode", "item", "skuid");
        String uom = getCell(cells, colIndex, "uom", "uomcode", "unit", "unitofmeasure", "baseuom");
        String tierStr = getCell(cells, colIndex, "tier", "tierfromqty", "tierfrom", "qty", "minqty", "fromqty");
        String priceStr =
                getCell(cells, colIndex, "price", "unitprice", "rate", "amount", "mrp", "retailprice", "tradeprice");

        UUID skuId = null;
        if (sku != null) {
            try {
                skuId = UUID.fromString(sku);
            } catch (IllegalArgumentException ignored) {
            }
        }

        BigDecimal tier = BigDecimal.ZERO;
        if (tierStr != null && !tierStr.isBlank()) {
            try {
                tier = new BigDecimal(tierStr.trim().replace(",", ""));
            } catch (NumberFormatException e) {
                return new ParsedRow(lineNo, sku, skuId, uom, null, null, "m3.price_list.line.tier_invalid");
            }
        }

        BigDecimal price = null;
        if (priceStr == null || priceStr.isBlank()) {
            return new ParsedRow(lineNo, sku, skuId, uom, tier, null, "m3.price_list.line.incomplete");
        }
        try {
            price = new BigDecimal(priceStr.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return new ParsedRow(lineNo, sku, skuId, uom, tier, null, "m3.price_list.line.price_invalid");
        }

        if (price.signum() < 0) {
            return new ParsedRow(lineNo, sku, skuId, uom, tier, price, "m3.price_list.line.price_negative");
        }

        return new ParsedRow(lineNo, sku, skuId, uom, tier, price, null);
    }

    private boolean isHeaderRow(List<String> tokens) {
        for (String token : tokens) {
            String norm = normalize(token);
            if (norm.equals("sku")
                    || norm.equals("skucode")
                    || norm.equals("code")
                    || norm.equals("barcode")
                    || norm.equals("item")
                    || norm.equals("price")
                    || norm.equals("unitprice")
                    || norm.equals("uom")
                    || norm.equals("tier")) {
                return true;
            }
        }
        return false;
    }

    private String detectDelimiter(String line) {
        if (line.contains("\t")) {
            return "\t";
        }
        if (line.contains(";") && !line.contains(",")) {
            return ";";
        }
        return ",";
    }

    private List<String> parseTokens(String line, String delimiter) {
        List<String> tokens = new ArrayList<>();
        char delim = delimiter.charAt(0);
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
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

    private String getCell(List<String> cells, Map<String, Integer> colIndex, String... aliases) {
        for (String alias : aliases) {
            Integer idx = colIndex.get(alias);
            if (idx != null && idx < cells.size()) {
                String val = cells.get(idx);
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            }
        }
        return null;
    }

    private String normalize(String header) {
        if (header == null) {
            return "";
        }
        return header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
