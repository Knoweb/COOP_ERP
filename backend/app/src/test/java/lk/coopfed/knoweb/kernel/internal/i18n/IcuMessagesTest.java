package lk.coopfed.knoweb.kernel.internal.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Messages;
import org.junit.jupiter.api.Test;

class IcuMessagesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IcuMessages messages = new IcuMessages(mapper, "i18n-fixtures/partial/");

    @Test
    void aMessageComesInTheCallersLanguageWithItsArguments() {
        assertThat(messages.t("a.greeting", Locale.forLanguageTag("si"), "Nimal"))
                .isEqualTo("ආයුබෝවන් Nimal");
        assertThat(messages.t("a.greeting", Locale.forLanguageTag("ta"), "Kumar"))
                .isEqualTo("வணக்கம் Kumar");
        assertThat(messages.t("a.greeting", Locale.forLanguageTag("en-GB"), "Ann"))
                .isEqualTo("Hello Ann");
        assertThat(messages.text("a.greeting", Locale.forLanguageTag("si"), "Nimal")
                        .fallback())
                .isFalse();
    }

    @Test
    void aLanguageTheCatalogueDoesNotHaveFallsBackToEnglishWithoutTheMark() {
        // A browser set to German gets English; that is not a missing translation, so no mark.
        assertThat(messages.text("a.greeting", Locale.GERMAN, "Anna"))
                .isEqualTo(new Messages.Text("Hello Anna", false));
        assertThat(messages.t("a.greeting", null, "Anna")).isEqualTo("Hello Anna");
    }

    @Test
    void anIdOneLanguageLacksFallsBackToEnglishAndSaysSo() {
        assertThat(messages.text("a.only_english", Locale.forLanguageTag("si")))
                .isEqualTo(new Messages.Text("English only", true));
    }

    @Test
    void anIdNoLanguageHasComesBackAsTheId() {
        assertThat(messages.text("a.unknown", Locale.ENGLISH)).isEqualTo(new Messages.Text("a.unknown", true));
    }

    @Test
    void pluralsAndNamedArgumentsWorkAndDigitsStayWesternInSinhalaAndTamil() {
        IcuMessages real = new IcuMessages(mapper);
        IcuMessages plural = new IcuMessages(mapper, "i18n-fixtures/plural/");
        Locale si = Locale.forLanguageTag("si");
        Locale ta = Locale.forLanguageTag("ta");

        // The plural rules of each language (CLDR): Sinhala counts 0 and 1 as "one", Tamil and
        // English 1 alone; the digits are Western in every language (DR-6).
        assertThat(plural.t("a.items", si, Map.of("count", 0))).isEqualTo("අයිතමය 0");
        assertThat(plural.t("a.items", si, Map.of("count", 1))).isEqualTo("අයිතමය 1");
        assertThat(plural.t("a.items", si, Map.of("count", 2))).isEqualTo("අයිතම 2");
        assertThat(plural.t("a.items", si, Map.of("count", 1234))).isEqualTo("අයිතම 1,234");
        assertThat(plural.t("a.items", ta, Map.of("count", 0))).isEqualTo("0 பொருட்கள்");
        assertThat(plural.t("a.items", ta, Map.of("count", 1))).isEqualTo("1 பொருள்");
        assertThat(plural.t("a.items", ta, Map.of("count", 2))).isEqualTo("2 பொருட்கள்");
        assertThat(plural.t("a.items", ta, Map.of("count", 1234))).isEqualTo("1,234 பொருட்கள்");
        assertThat(plural.t("a.items", Locale.ENGLISH, Map.of("count", 0))).isEqualTo("0 items");
        assertThat(plural.t("a.items", Locale.ENGLISH, Map.of("count", 1))).isEqualTo("1 item");
        assertThat(plural.t("a.items", Locale.ENGLISH, Map.of("count", 2))).isEqualTo("2 items");
        assertThat(plural.t("a.items", Locale.ENGLISH, Map.of("count", 1234))).isEqualTo("1,234 items");

        // A select form in each language.
        assertThat(plural.t("a.who", si, Map.of("gender", "female"))).isEqualTo("ඇය අත්සන් කළා");
        assertThat(plural.t("a.who", ta, Map.of("gender", "other"))).isEqualTo("அவர்கள் கையொப்பமிட்டார்");
        assertThat(plural.t("a.who", Locale.ENGLISH, Map.of("gender", "male"))).isEqualTo("He signed");

        // A plural id only English has: the English form, its plural rule, and the mark.
        assertThat(plural.text("a.only_english_plural", si, Map.of("count", 1)))
                .isEqualTo(new Messages.Text("1 receipt", true));
        assertThat(plural.text("a.only_english_plural", ta, Map.of("count", 3)))
                .isEqualTo(new Messages.Text("3 receipts", true));

        assertThat(real.t("scope.required", Locale.ENGLISH)).isEqualTo("Select the entity you are working for");
    }

    @Test
    void theSameIdFormattedTwiceComesFromOneCachedFormatAndStaysRight() {
        IcuMessages plural = new IcuMessages(mapper, "i18n-fixtures/plural/");
        // The format is parsed once per language and id and cloned per call, so a second call
        // with other arguments is not answered with the first call's text.
        assertThat(plural.t("a.items", Locale.ENGLISH, Map.of("count", 1))).isEqualTo("1 item");
        assertThat(plural.t("a.items", Locale.ENGLISH, Map.of("count", 5))).isEqualTo("5 items");
        assertThat(plural.t("a.items", Locale.forLanguageTag("ta"), Map.of("count", 1)))
                .isEqualTo("1 பொருள்");
    }

    @Test
    void inStrictModeAnIdNoLanguageHasThrows() {
        IcuMessages strict = new IcuMessages(mapper, "i18n-fixtures/partial/", true);
        assertThatThrownBy(() -> strict.text("a.unknown", Locale.ENGLISH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a.unknown");
        // A missing translation is still a fallback, not a missing id.
        assertThat(strict.text("a.only_english", Locale.forLanguageTag("si")))
                .isEqualTo(new Messages.Text("English only", true));
    }

    @Test
    void aBrokenCatalogueFileStopsTheStart() {
        assertThatThrownBy(() -> new IcuMessages(mapper, "i18n-fixtures/broken/"))
                .hasMessageContaining("i18n-fixtures/broken/si.json cannot be read");
    }

    @Test
    void aMissingCatalogueFileStopsTheStart() {
        assertThatThrownBy(() -> new IcuMessages(mapper, "i18n-fixtures/nowhere/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("i18n-fixtures/nowhere/en.json is not on the class path");
    }
}
