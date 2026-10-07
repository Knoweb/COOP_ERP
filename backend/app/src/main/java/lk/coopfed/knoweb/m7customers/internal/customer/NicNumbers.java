package lk.coopfed.knoweb.m7customers.internal.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The National Identity Card number (27A: "NicHash"): the old form, nine digits and V or X
 * ({@code YYDDDSSSCV}), and the new one, twelve digits ({@code YYYYDDDSSSSC}). The same card has
 * both forms, so the number is brought to one <b>canonical</b> form before anything is computed
 * from it: the twelve digits, an old form becoming {@code 19} + {@code YYDDD} + {@code 0} +
 * {@code SSSC} with the letter dropped (the standard conversion; old-form cards were issued to
 * people born in the 1900s only). Wave 2, M7CR-02: before this, {@code 851234567V} and {@code
 * 198512304567} were two people to the duplicate guard.
 *
 * <p>What is kept of a NIC: a keyed hash ({@link NicHasher}) and the last four characters of the
 * canonical form (for an officer to confirm an identity, 27A section 6.1). The number itself is
 * never stored, logged, audited or published. {@link #legacySha256} is the scheme the rows
 * carried before the hash was keyed (plain SHA-256 of {@code "nic:" + form as typed}); it is
 * still computed, to find and re-key those rows, never to store a new one.
 */
public final class NicNumbers {

    private static final Pattern OLD = Pattern.compile("[0-9]{9}[VX]");
    private static final Pattern NEW = Pattern.compile("[0-9]{12}");

    private NicNumbers() {}

    /** The number as typed, without spaces and upper case; empty when it has neither form. */
    public static Optional<String> normalise(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String nic = typed.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        return OLD.matcher(nic).matches() || NEW.matcher(nic).matches() ? Optional.of(nic) : Optional.empty();
    }

    /** The canonical twelve-digit form of the number as typed; empty when it has neither form. */
    public static Optional<String> canonical(String typed) {
        return normalise(typed).map(NicNumbers::toCanonical);
    }

    private static String toCanonical(String normalised) {
        if (NEW.matcher(normalised).matches()) {
            return normalised;
        }
        // YYDDDSSSCV -> 19 YYDDD 0 SSSC
        return "19" + normalised.substring(0, 5) + "0" + normalised.substring(5, 9);
    }

    /**
     * Every form a legacy row (plain SHA-256 of the form as typed) may hold for this canonical
     * number: the canonical form itself, and when the number has an old form, that form with V
     * and with X. At most three.
     */
    public static List<String> legacyForms(String canonical) {
        List<String> forms = new ArrayList<>();
        forms.add(canonical);
        if (canonical.startsWith("19") && canonical.charAt(7) == '0') {
            String old = canonical.substring(2, 7) + canonical.substring(8, 12);
            forms.add(old + "V");
            forms.add(old + "X");
        }
        return List.copyOf(forms);
    }

    /** The scheme before wave 2: SHA-256 of {@code "nic:" + form}, 64 hex characters. */
    public static String legacySha256(String form) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(("nic:" + form).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** The last four characters of the canonical form: the same four whichever card was typed. */
    public static String last4(String canonical) {
        return canonical.substring(canonical.length() - 4);
    }
}
