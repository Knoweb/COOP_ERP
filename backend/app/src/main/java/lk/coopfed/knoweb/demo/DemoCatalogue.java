package lk.coopfed.knoweb.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The demo catalogue, read from {@code demo/catalogue.psv} (one SKU per line, fields separated by
 * |; the header of the file names them). A data file and not Java constants, so that whoever runs
 * a demo can change a name or a price without touching code.
 */
final class DemoCatalogue {

    static final String RESOURCE = "/demo/catalogue.psv";

    /** GS1 Sri Lanka's prefix, then a demo company number: the barcodes are made up but well formed. */
    private static final String GTIN_STEM = "4790000";

    private DemoCatalogue() {}

    record Item(
            int lineNo,
            String nameEn,
            String nameSi,
            String nameTa,
            String baseUom,
            boolean soldByWeight,
            boolean batchTracked,
            boolean expiryTracked,
            boolean hasPrintedMrp,
            String taxCode,
            String packUom,
            BigDecimal packFactor,
            BigDecimal federationPrice,
            BigDecimal federationBulkPrice,
            BigDecimal distributorPrice,
            BigDecimal unitCost,
            BigDecimal federationQty,
            BigDecimal distributorQty,
            BigDecimal printedMrp,
            Integer shelfLifeDays) {

        /** An EAN-13 for a packed item; none for goods sold loose by weight. */
        String barcode() {
            if (soldByWeight) {
                return null;
            }
            return ean13(GTIN_STEM + String.format("%05d", lineNo));
        }
    }

    static List<Item> load() {
        try (InputStream in = DemoCatalogue.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The demo catalogue " + RESOURCE + " is not on the class path");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            List<Item> items = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                items.add(parse(items.size() + 1, line));
            }
            return List.copyOf(items);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Item parse(int lineNo, String line) {
        String[] f = line.split("\\|", -1);
        if (f.length != 19) {
            throw new IllegalStateException(
                    "The demo catalogue line " + lineNo + " has " + f.length + " fields, not 19: " + line);
        }
        return new Item(
                lineNo,
                f[0].strip(),
                f[1].strip(),
                f[2].strip(),
                f[3].strip(),
                yes(f[4]),
                yes(f[5]),
                yes(f[6]),
                yes(f[7]),
                f[8].strip(),
                blankToNull(f[9]),
                decimal(f[10]),
                decimal(f[11]),
                decimal(f[12]),
                decimal(f[13]),
                decimal(f[14]),
                decimal(f[15]),
                decimal(f[16]),
                decimal(f[17]),
                f[18].isBlank() ? null : Integer.valueOf(f[18].strip()));
    }

    private static boolean yes(String field) {
        return "Y".equalsIgnoreCase(field.strip());
    }

    private static String blankToNull(String field) {
        return field.isBlank() ? null : field.strip();
    }

    private static BigDecimal decimal(String field) {
        return field.isBlank() ? null : new BigDecimal(field.strip());
    }

    /** The twelve digits and their GS1 check digit. */
    static String ean13(String twelve) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = twelve.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        int check = (10 - sum % 10) % 10;
        return twelve + check;
    }
}
