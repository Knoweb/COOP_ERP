package lk.coopfed.knoweb.kernel.internal.i18n;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lk.coopfed.knoweb.kernel.api.TextNormaliser;

/**
 * NFC on every text column an entity writes (doc 19 section 5.4: "all text is normalised to
 * Unicode NFC at the write boundary"). Applied to every String attribute automatically, so a
 * module cannot forget it; a code or an id is ASCII and passes through unchanged. It changes
 * the encoding only, never the content: white space is the handler's business
 * ({@link TextNormaliser#nfc} strips typed text), not the write boundary's, which would
 * otherwise alter a value whose spaces mean something. Reads are untouched: what is stored
 * is already NFC.
 *
 * <p>19A section 6 names a marker type {@code I18nText} for the converter to apply to. Every
 * String attribute is converted instead: a marker a module forgets on one column is exactly
 * the column that ends up with two spellings of one name, and NFC on a code is harmless.
 * Recorded as a deviation in docs/PROGRESS.md.
 */
@Converter(autoApply = true)
public class NfcConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return TextNormaliser.nfcOnly(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData;
    }
}
