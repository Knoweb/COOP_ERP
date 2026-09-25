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
        // A message written for this test through the fixture folder would need three files;
        // the plural form is exercised on an inline catalogue instead.
        IcuMessages inline = new IcuMessages(mapper, "i18n-fixtures/plural/");
        assertThat(inline.t("a.items", Locale.forLanguageTag("si"), Map.of("count", 1)))
                .isEqualTo("අයිතම 1");
        assertThat(inline.t("a.items", Locale.forLanguageTag("ta"), Map.of("count", 1234)))
                .isEqualTo("1,234 பொருட்கள்");
        assertThat(inline.t("a.items", Locale.ENGLISH, Map.of("count", 1))).isEqualTo("1 item");
        assertThat(inline.t("a.items", Locale.ENGLISH, Map.of("count", 2))).isEqualTo("2 items");
        assertThat(real.t("scope.required", Locale.ENGLISH)).isEqualTo("Select the entity you are working for");
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
