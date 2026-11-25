package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.util.Optional;

/**
 * Central parser for in-store 20–29 variable-weight and batch-reference labels
 * (doc 22 section 3.3, doc 26 section 3.6, doc 26A section 7; ADR-43; ticket TILL-SELL-16).
 *
 * <p>A 13-digit EAN-13 barcode with GS1 prefix 20–29 encodes:
 * <ul>
 *   <li>Prefix 20–24: 6-digit SKU code + 4-digit weight in exact integer grams (weight &gt; 0)
 *   <li>Prefix 25–29: 6-digit SKU code + 4-digit batch reference
 * </ul>
 *
 * <p>Never derives or returns a price (ADR-43).
 */
public final class WeighedLabelParser {

    public enum Kind {
        WEIGHED,
        BATCH
    }

    public record ParsedLabel(
            String rawData, int prefix, String code, Integer weightGrams, String batchRef, Kind kind) {}

    private WeighedLabelParser() {}

    /**
     * Parses a 13-digit in-store label with GS1 prefix 20–29.
     * Returns empty if the string is null, not 13 digits, has an invalid EAN-13 check digit,
     * has a prefix outside 20–29, or has a non-positive weight for prefixes 20–24.
     */
    public static Optional<ParsedLabel> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String trimmed = value.strip();
        if (trimmed.length() != 13 || !GtinParser.allDigits(trimmed)) {
            return Optional.empty();
        }

        int prefix = Integer.parseInt(trimmed.substring(0, 2));
        if (prefix < 20 || prefix > 29) {
            return Optional.empty();
        }

        if (!GtinParser.isValidGtin(trimmed)) {
            return Optional.empty();
        }

        String code = trimmed.substring(2, 8);
        String tail = trimmed.substring(8, 12);

        if (prefix <= 24) {
            int grams = Integer.parseInt(tail);
            if (grams <= 0) {
                return Optional.empty();
            }
            return Optional.of(new ParsedLabel(trimmed, prefix, code, grams, null, Kind.WEIGHED));
        } else {
            return Optional.of(new ParsedLabel(trimmed, prefix, code, null, tail, Kind.BATCH));
        }
    }

    /**
     * True if the string has the length, all-digits format, and prefix (20–29) of an in-store label.
     */
    public static boolean isPotentialInStoreLabel(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.strip();
        if (trimmed.length() != 13 || !GtinParser.allDigits(trimmed)) {
            return false;
        }
        int prefix = Integer.parseInt(trimmed.substring(0, 2));
        return prefix >= 20 && prefix <= 29;
    }
}
