package lk.coopfed.knoweb.kernel.api;

import java.text.Normalizer;

/**
 * Unicode NFC at the write boundary (doc 19 section 5.4): Sinhala and Tamil have several
 * encodings for visually identical text, and two spellings of one name would neither sort
 * together nor match. Every text column is normalised on write by the kernel's JPA converter;
 * a handler that writes text through JDBC calls this itself. The till applies the same rule
 * in Kotlin ({@code Normalizer.normalize(s, NFC)}).
 */
public final class TextNormaliser {

    private TextNormaliser() {}

    /** NFC, with leading and trailing white space removed; null stays null. */
    public static String nfc(String text) {
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return Normalizer.isNormalized(stripped, Normalizer.Form.NFC)
                ? stripped
                : Normalizer.normalize(stripped, Normalizer.Form.NFC);
    }
}
