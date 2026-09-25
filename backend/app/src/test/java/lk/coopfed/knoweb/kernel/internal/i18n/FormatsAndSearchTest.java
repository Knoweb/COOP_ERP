package lk.coopfed.knoweb.kernel.internal.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import lk.coopfed.knoweb.kernel.api.SearchSupport;
import lk.coopfed.knoweb.kernel.api.TextNormaliser;
import org.junit.jupiter.api.Test;

class FormatsAndSearchTest {

    private final IcuFormats formats = new IcuFormats("Asia/Colombo");

    @Test
    void moneyAndDatesHaveOneShapeInEveryLanguageWithWesternDigits() {
        BigDecimal amount = new BigDecimal("1234567.5");
        for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.forLanguageTag("si"), Locale.forLanguageTag("ta")}) {
            assertThat(formats.money(amount, locale)).isEqualTo("1,234,567.50");
            assertThat(formats.quantity(new BigDecimal("12.500"), locale)).isEqualTo("12.500");
            assertThat(formats.quantity(new BigDecimal("1234"), locale)).isEqualTo("1,234");
        }
        assertThat(formats.moneyWithCurrency(amount, Locale.ENGLISH)).isEqualTo("Rs 1,234,567.50");
        assertThat(formats.moneyWithCurrency(amount, Locale.forLanguageTag("si")))
                .isEqualTo("රු. 1,234,567.50");
        assertThat(formats.moneyWithCurrency(amount, Locale.forLanguageTag("ta")))
                .isEqualTo("ரூ. 1,234,567.50");
        assertThat(formats.date(LocalDate.of(2026, 9, 24))).isEqualTo("24/09/2026");
        // 08:35 UTC is 14:05 in Colombo.
        assertThat(formats.dateTime(Instant.parse("2026-09-24T08:35:00Z"))).isEqualTo("24/09/2026 14:05");
    }

    @Test
    void textIsNormalisedToNfcAndStripped() {
        // U+0DCA U+200D U+0DBB (Sinhala rakaransaya) is already NFC; a decomposed Latin é is not.
        String decomposed = "Caf" + "é";
        assertThat(TextNormaliser.nfc("  " + decomposed + " ")).isEqualTo("Café");
        assertThat(TextNormaliser.nfc(null)).isNull();
        String sinhala = "ක්‍ර";
        assertThat(TextNormaliser.nfc(sinhala)).isEqualTo(sinhala);
    }

    @Test
    void listsAreOrderedByTheDisplayLanguageWithEnglishAsTheTieBreaker() {
        assertThat(SearchSupport.orderByDisplayLanguage("legal_name", Locale.forLanguageTag("si")))
                .isEqualTo(
                        "coalesce(legal_name_si, legal_name_en) COLLATE kernel.si_icu, legal_name_en COLLATE kernel.en_icu");
        assertThat(SearchSupport.orderByDisplayLanguage("legal_name", Locale.ENGLISH))
                .isEqualTo("legal_name_en COLLATE kernel.en_icu");
        assertThat(SearchSupport.orderByDisplayLanguage("legal_name", Locale.GERMAN))
                .isEqualTo("legal_name_en COLLATE kernel.en_icu");
        assertThat(SearchSupport.collation(Locale.forLanguageTag("ta"))).isEqualTo("kernel.ta_icu");
        assertThatThrownBy(() -> SearchSupport.orderByDisplayLanguage("legal_name; drop", Locale.ENGLISH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSearchMatchesAllThreeNameColumnsWithAnEscapedFragment() {
        assertThat(SearchSupport.matchAnyLanguage("legal_name"))
                .isEqualTo("(legal_name_en ILIKE ? OR legal_name_si ILIKE ? OR legal_name_ta ILIKE ?)");
        assertThat(SearchSupport.pattern("50% off_")).isEqualTo("%50\\% off\\_%");
        assertThat(SearchSupport.pattern(" Café ")).isEqualTo("%Café%");
    }
}
