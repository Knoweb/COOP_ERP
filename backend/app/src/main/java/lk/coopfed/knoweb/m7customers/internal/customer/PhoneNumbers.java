package lk.coopfed.knoweb.m7customers.internal.customer;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Sri Lankan phone numbers in E.164 (27A section 6: "phone valid E.164-normalised"). Accepted as
 * typed at a counter: 0771234567, 077 123 4567, 94771234567 or +94771234567; stored as
 * +94771234567. A number is personal data: it is never logged, audited or put in an event.
 */
public final class PhoneNumbers {

    private static final Pattern NATIONAL = Pattern.compile("0([1-9][0-9]{8})");
    private static final Pattern INTERNATIONAL = Pattern.compile("\\+?94([1-9][0-9]{8})");

    private PhoneNumbers() {}

    /** The number in E.164, or empty when it is not a Sri Lankan number. */
    public static Optional<String> normalise(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String digits = typed.replaceAll("[\\s\\-()]", "");
        var national = NATIONAL.matcher(digits);
        if (national.matches()) {
            return Optional.of("+94" + national.group(1));
        }
        var international = INTERNATIONAL.matcher(digits);
        if (international.matches()) {
            return Optional.of("+94" + international.group(1));
        }
        return Optional.empty();
    }
}
