package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * GTIN arithmetic (22A section 4: "GtinParser (validates check digit; splits AI 01/10/17 already
 * parsed by the till)").
 *
 * <p>A GTIN-8, -12, -13 or -14 ends in a check digit: from the right, the other digits are
 * weighted 3, 1, 3, 1 ..., summed, and the check digit makes the total a multiple of ten. The
 * same rule serves EAN-8, UPC-A and EAN-13 (GS1 General Specifications, section 7.9).
 *
 * <p>A GS1 element string, as a 2D code or a GS1-128 carries it, is a sequence of application
 * identifiers: 01 (GTIN-14, fixed), 17 (expiry YYMMDD, fixed), 10 (lot, up to 20 characters,
 * ended by the group separator U+001D or the end of the string). The till normally splits it and
 * sends gtin, lot and expiry apart (doc 22 section 3.3); this parser is the same split on the
 * server, for a code sent whole.
 */
public final class GtinParser {

    /** The application identifiers this parser understands. */
    public record Gs1Elements(String gtin, String lot, LocalDate expiry) {}

    /** ASCII group separator: ends a variable-length element such as the lot. */
    static final char GROUP_SEPARATOR = '\u001D';

    private static final String AI_GTIN = "01";
    private static final String AI_LOT = "10";
    private static final String AI_EXPIRY = "17";
    private static final int GTIN14_LENGTH = 14;
    private static final int EXPIRY_LENGTH = 6;
    private static final int LOT_MAX_LENGTH = 20;

    private GtinParser() {}

    /** True for a string of 8, 12, 13 or 14 digits whose last digit is the right check digit. */
    public static boolean isValidGtin(String value) {
        if (value == null) {
            return false;
        }
        int length = value.length();
        if (length != 8 && length != 12 && length != 13 && length != 14) {
            return false;
        }
        return allDigits(value) && checkDigit(value.substring(0, length - 1)) == value.charAt(length - 1) - '0';
    }

    /** The check digit of a run of digits (the digits without the check digit). */
    static int checkDigit(String digitsWithoutCheck) {
        int sum = 0;
        int weight = 3;
        for (int i = digitsWithoutCheck.length() - 1; i >= 0; i--) {
            sum += (digitsWithoutCheck.charAt(i) - '0') * weight;
            weight = weight == 3 ? 1 : 3;
        }
        return (10 - sum % 10) % 10;
    }

    /**
     * Splits a GS1 element string into GTIN, lot and expiry. Empty when the string does not start
     * with AI 01, carries an unknown identifier, or its GTIN has a wrong check digit. Identifiers
     * other than 01, 10 and 17 are not understood: the till sends those parsed.
     */
    public static Optional<Gs1Elements> parseElementString(String value, LocalDate today) {
        if (value == null || !value.startsWith(AI_GTIN)) {
            return Optional.empty();
        }

        String gtin = null;
        String lot = null;
        LocalDate expiry = null;

        int position = 0;
        while (position < value.length()) {
            if (position + 2 > value.length()) {
                return Optional.empty();
            }
            String ai = value.substring(position, position + 2);
            position += 2;

            switch (ai) {
                case AI_GTIN -> {
                    if (position + GTIN14_LENGTH > value.length()) {
                        return Optional.empty();
                    }
                    gtin = value.substring(position, position + GTIN14_LENGTH);
                    if (!isValidGtin(gtin)) {
                        return Optional.empty();
                    }
                    position += GTIN14_LENGTH;
                }
                case AI_EXPIRY -> {
                    if (position + EXPIRY_LENGTH > value.length()) {
                        return Optional.empty();
                    }
                    expiry = expiryDate(value.substring(position, position + EXPIRY_LENGTH), today);
                    if (expiry == null) {
                        return Optional.empty();
                    }
                    position += EXPIRY_LENGTH;
                }
                case AI_LOT -> {
                    int end = value.indexOf(GROUP_SEPARATOR, position);
                    if (end < 0) {
                        end = value.length();
                    }
                    lot = value.substring(position, end);
                    if (lot.isEmpty() || lot.length() > LOT_MAX_LENGTH) {
                        return Optional.empty();
                    }
                    position = Math.min(end + 1, value.length());
                }
                default -> {
                    return Optional.empty();
                }
            }
        }

        return gtin == null ? Optional.empty() : Optional.of(new Gs1Elements(gtin, lot, expiry));
    }

    /**
     * AI 17 is YYMMDD; a day of 00 means the last day of the month. The century is the one that
     * puts the year within 49 years ahead or 50 behind {@code today} (GS1 section 7.12); the
     * caller passes the date from the injected Clock (19A section 13).
     */
    static LocalDate expiryDate(String yymmdd, LocalDate today) {
        if (yymmdd == null || yymmdd.length() != EXPIRY_LENGTH || !allDigits(yymmdd)) {
            return null;
        }
        int yy = Integer.parseInt(yymmdd.substring(0, 2));
        int month = Integer.parseInt(yymmdd.substring(2, 4));
        int day = Integer.parseInt(yymmdd.substring(4, 6));

        int currentYear = today.getYear();
        int century = currentYear - currentYear % 100;
        int year = century + yy;
        if (year - currentYear > 49) {
            year -= 100;
        } else if (currentYear - year > 50) {
            year += 100;
        }

        try {
            YearMonth yearMonth = YearMonth.of(year, month);
            return day == 0 ? yearMonth.atEndOfMonth() : yearMonth.atDay(day);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** A GTIN without its leading zeros: 04791234567890 and 4791234567890 are the same item. */
    public static String withoutLeadingZeros(String gtin) {
        int start = 0;
        while (start < gtin.length() - 1 && gtin.charAt(start) == '0') {
            start++;
        }
        return gtin.substring(start);
    }

    static boolean allDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
