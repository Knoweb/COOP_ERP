package lk.coopfed.knoweb.kernel.api;

import java.text.Normalizer;

/**
 * Unicode NFC at the write boundary (doc 19 section 5.4): Sinhala and Tamil have several
 * encodings for visually identical text, and two spellings of one name would neither sort
 * together nor match. Every text column is normalised on write by the kernel's JPA converter
 * ({@link #nfcOnly}); a handler that writes text through JDBC, or compares what the user typed
 * with a stored value in a native query, calls {@link #nfc} itself, because a decomposed
 * spelling in the parameter would miss the NFC row. The till applies the same rule in Kotlin
 * ({@code Normalizer.normalize(s, NFC)}).
 */
public final class TextNormaliser {

    private TextNormaliser() {}

    /** NFC, with leading and trailing white space removed: what a handler does to typed text; null stays null. */
    public static String nfc(String text) {
        return text == null ? null : nfcOnly(text.strip());
    }

    /**
     * NFC and nothing else: the write boundary's rule (doc 19 section 5.4), which changes the
     * encoding of the text and never its content. A value whose spaces mean something (a
     * prefix, a padded code) keeps them; null stays null.
     */
    public static String nfcOnly(String text) {
        if (text == null) {
            return null;
        }
        return Normalizer.isNormalized(text, Normalizer.Form.NFC)
                ? text
                : Normalizer.normalize(text, Normalizer.Form.NFC);
    }
}
