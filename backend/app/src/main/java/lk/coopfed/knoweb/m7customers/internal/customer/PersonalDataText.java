package lk.coopfed.knoweb.m7customers.internal.customer;

import java.util.Map;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.ProblemException;

/**
 * The value guard on officer free text (wave 2, M7CR-10;
 * {@code docs/progress/deviations/2026-10-06-wave2-m7-erasure-and-retention.md} (2)): a reason,
 * reference, note or outcome that carries a Sri Lankan phone number ({@code 0xxxxxxxxx},
 * {@code +94xxxxxxxxx}) or a NIC ({@code nine digits and V or X}, {@code twelve digits}) is
 * refused with {@code m7.field.personal_data}. These columns are retained after an erasure (the
 * society's record of its own decisions, an issued document, the kernel's audit), so the data
 * must not go in. Spaces and dashes are stripped before matching, so {@code 077-123 4567} is
 * caught. A name cannot be detected and is the accepted limitation (module README).
 */
public final class PersonalDataText {

    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(0[0-9]{9}|\\+94[0-9]{9})(?![0-9])");
    // Bounded by non-digits only: spaces are stripped before matching, so "card 900000001V" is
    // "CARD900000001V" and a letter may stand right before the number.
    private static final Pattern NIC = Pattern.compile("(?<![0-9])([0-9]{9}[VX]|[0-9]{12})(?![0-9])");

    private PersonalDataText() {}

    /** The text, stripped, when it carries no phone number or NIC; {@code m7.field.personal_data} naming the field otherwise. */
    public static String require(String value, String field) {
        if (value == null) {
            return null;
        }
        if (carriesPersonalData(value)) {
            throw new ProblemException("m7.field.personal_data", Map.of("field", field));
        }
        return value.strip();
    }

    static boolean carriesPersonalData(String value) {
        String compact = value.replaceAll("[\\s\\-]", "").toUpperCase(java.util.Locale.ROOT);
        return PHONE.matcher(compact).find() || NIC.matcher(compact).find();
    }
}
