package lk.coopfed.knoweb.m7customers.internal.customer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The National Identity Card number (27A: "NicHash"): the old form, nine digits and V or X, and
 * the new one, twelve digits. Only its SHA-256 hash (to find a second registration of the same
 * person) and its last four characters (for an officer to confirm an identity) are kept; the
 * number itself is never stored, logged, audited or published.
 */
public final class NicNumbers {

    private static final Pattern OLD = Pattern.compile("[0-9]{9}[VX]");
    private static final Pattern NEW = Pattern.compile("[0-9]{12}");

    private NicNumbers() {}

    /** The number without spaces, upper case; empty when it has neither form. */
    public static Optional<String> normalise(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String nic = typed.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        return OLD.matcher(nic).matches() || NEW.matcher(nic).matches() ? Optional.of(nic) : Optional.empty();
    }

    /** The hash stored in customer.nic_hash: 64 hex characters. */
    public static String hash(String normalised) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256").digest(("nic:" + normalised).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    public static String last4(String normalised) {
        return normalised.substring(normalised.length() - 4);
    }
}
