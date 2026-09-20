package lk.coopfed.knoweb.kernel.internal.stub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonMessagesTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonMessages messages = new JsonMessages(mapper, "i18n-fixtures/partial/");

    @Test
    void aMessageComesInTheCallersLanguageWithItsArguments() {
        assertThat(messages.t("a.greeting", Locale.forLanguageTag("si"), "Nimal")).isEqualTo("ආයුබෝවන් Nimal");
        assertThat(messages.t("a.greeting", Locale.forLanguageTag("ta"), "Kumar")).isEqualTo("வணக்கம் Kumar");
        assertThat(messages.t("a.greeting", Locale.forLanguageTag("en-GB"), "Ann")).isEqualTo("Hello Ann");
    }

    @Test
    void aLanguageTheCatalogueDoesNotHaveFallsBackToEnglish() {
        // A browser set to German used to get the message id as the text.
        assertThat(messages.t("a.greeting", Locale.GERMAN, "Anna")).isEqualTo("Hello Anna");
        assertThat(messages.t("a.greeting", null, "Anna")).isEqualTo("Hello Anna");
    }

    @Test
    void anIdOneLanguageLacksFallsBackToEnglish() {
        assertThat(messages.t("a.only_english", Locale.forLanguageTag("si"))).isEqualTo("English only");
    }

    @Test
    void anIdNoLanguageHasComesBackAsTheId() {
        assertThat(messages.t("a.unknown", Locale.ENGLISH)).isEqualTo("a.unknown");
    }

    @Test
    void aBrokenCatalogueFileStopsTheStart() {
        assertThatThrownBy(() -> new JsonMessages(mapper, "i18n-fixtures/broken/"))
                .hasMessageContaining("i18n-fixtures/broken/si.json cannot be read");
    }

    @Test
    void aMissingCatalogueFileStopsTheStart() {
        assertThatThrownBy(() -> new JsonMessages(mapper, "i18n-fixtures/nowhere/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("i18n-fixtures/nowhere/en.json is not on the class path");
    }

    @Test
    void theRealCataloguesLoad() {
        assertThat(new JsonMessages(mapper).t("scope.required", Locale.ENGLISH))
                .isEqualTo("Select the entity you are working for");
    }
}
