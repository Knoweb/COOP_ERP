package lk.coopfed.knoweb.m2catalogue.internal.steward;

import static org.assertj.core.api.Assertions.assertThat;

import lk.coopfed.knoweb.m2catalogue.internal.steward.Transliterator.TransliterationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransliteratorTest {

    private Transliterator transliterator;

    @BeforeEach
    void setUp() {
        transliterator = new Transliterator();
    }

    @Test
    void transliteratesSinhalaToLatin() {
        String latin = transliterator.toLatin("කිරි පිටි");
        assertThat(latin).isNotNull().contains("kiri").contains("piti");
    }

    @Test
    void transliteratesTamilToLatin() {
        String latin = transliterator.toLatin("பால் மா");
        assertThat(latin).isNotNull().contains("paal").contains("maa");
    }

    @Test
    void normalizesNfcAndPreservesLatinCharacters() {
        String latin = transliterator.toLatin("Milk powder 400g! (Special)");
        assertThat(latin).isEqualTo("Milk powder 400g! (Special)");
    }

    @Test
    void fillsMissingSinhalaAndTamilFromEnglish() {
        TransliterationResult result = transliterator.transliterateMissing("Milk powder 400g", null, null);
        assertThat(result.nameEn()).isEqualTo("Milk powder 400g");
        assertThat(result.nameSi()).isNotBlank().contains("කිරි");
        assertThat(result.nameTa()).isNotBlank().contains("பால்");
    }

    @Test
    void preservesExistingTranslationsWhenPresent() {
        TransliterationResult result = transliterator.transliterateMissing("Tea", "විශේෂ තේ", "சிறப்பு தேநீர்");
        assertThat(result.nameEn()).isEqualTo("Tea");
        assertThat(result.nameSi()).isEqualTo("විශේෂ තේ");
        assertThat(result.nameTa()).isEqualTo("சிறப்பு தேநீர்");
    }
}
