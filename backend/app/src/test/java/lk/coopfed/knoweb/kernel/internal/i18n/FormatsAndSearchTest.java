package lk.coopfed.knoweb.kernel.internal.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import lk.coopfed.knoweb.kernel.api.SearchSupport;
import lk.coopfed.knoweb.kernel.api.TextNormaliser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

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
        // "Rs" in every language (doc 19 section 5.1): one figure on the receipt, the tax document and the screen.
        assertThat(formats.moneyWithCurrency(amount, Locale.ENGLISH)).isEqualTo("Rs 1,234,567.50");
        assertThat(formats.moneyWithCurrency(amount, Locale.forLanguageTag("si")))
                .isEqualTo("Rs 1,234,567.50");
        assertThat(formats.moneyWithCurrency(amount, Locale.forLanguageTag("ta")))
                .isEqualTo("Rs 1,234,567.50");
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
        // The write boundary changes the encoding alone: the converter keeps the spaces.
        assertThat(TextNormaliser.nfcOnly("  " + decomposed + " ")).isEqualTo("  Café ");
        assertThat(new NfcConverter().convertToDatabaseColumn(" " + decomposed)).isEqualTo(" Café");
        assertThat(TextNormaliser.nfcOnly(null)).isNull();
    }

    @Test
    void theJavaCollatorSortsTheTestSetAsPostgresDoes() throws Exception {
        // The same curated set CollationPostgresIntegrationTest sorts in PostgreSQL, sorted here
        // with the ICU4J collator of SearchSupport.comparator: one order on both sides.
        Map<String, Object> set;
        try (InputStream in = new ClassPathResource("i18n/sort-test-set.yaml").getInputStream()) {
            set = new Yaml().load(in);
        }
        for (String language : List.of("si", "ta", "en")) {
            @SuppressWarnings("unchecked")
            List<String> expected = (List<String>) set.get(
                    switch (language) {
                        case "si" -> "sinhala_in_order";
                        case "ta" -> "tamil_in_order";
                        default -> "english_in_order";
                    });
            List<String> shuffled = new ArrayList<>(expected);
            Collections.reverse(shuffled);
            shuffled.sort(SearchSupport.comparator(Locale.forLanguageTag(language)));
            assertThat(shuffled).as(language).isEqualTo(expected);
        }
        assertThat(Stream.of("b", null, "a")
                        .sorted(SearchSupport.comparator(Locale.ENGLISH))
                        .toList())
                .containsExactly("a", "b", null);
    }

    @Test
    void listsAreOrderedByTheDisplayLanguageWithEnglishAsTheTieBreaker() {
        // The untranslated rows come after the translated ones, in English; a coalesce into the
        // Sinhala collation would have put them first (Latin sorts before Sinhala).
        assertThat(SearchSupport.orderByDisplayLanguage("legal_name", Locale.forLanguageTag("si")))
                .isEqualTo(
                        "(legal_name_si IS NULL), legal_name_si COLLATE kernel.si_icu, legal_name_en COLLATE kernel.en_icu");
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
