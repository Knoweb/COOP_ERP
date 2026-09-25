package lk.coopfed.knoweb.kernel.internal.i18n;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lk.coopfed.knoweb.kernel.api.TextNormaliser;

/**
 * NFC on every text column an entity writes (doc 19 section 5.4: "all text is normalised to
 * Unicode NFC at the write boundary"). Applied to every String attribute automatically, so a
 * module cannot forget it; a code or an id is ASCII and passes through unchanged. Reads are
 * untouched: what is stored is already NFC.
 */
@Converter(autoApply = true)
public class NfcConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return TextNormaliser.nfc(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData;
    }
}
