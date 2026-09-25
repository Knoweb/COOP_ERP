package lk.coopfed.knoweb.kernel.internal.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.util.ULocale;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The catalogue on ICU MessageFormat (19A section 6), replacing the 17A stub on
 * java.text.MessageFormat. The three files {@code i18n/{en,si,ta}.json} are read at start; a
 * missing or broken one stops the start, because answering every Sinhala user with message
 * ids because of one stray comma is the kind of fault nobody notices until a shop calls.
 *
 * <p>ICU adds what the stub lacked: plural and select forms ({@code {count, plural, one {# item}
 * other {# items}}}) and named arguments; positional arguments still work, so no catalogue
 * changed. Numbers inside a message are written with Western Arabic digits in every language
 * (DR-6): the format is built for the language with the LATN numbering system.
 *
 * <p>Fallback is said, not hidden: {@link Messages.Text#fallback()} is true when the text came
 * from English because the requested language lacks the id. An id no language has is
 * returned as the id and logged; the build check makes that impossible for ids in the code.
 */
@Component
public class IcuMessages implements Messages {

    private static final Logger log = LoggerFactory.getLogger(IcuMessages.class);

    static final List<String> LANGUAGES = List.of("en", "si", "ta");
    private static final String FALLBACK = "en";

    private final Map<String, Map<String, String>> catalogues = new LinkedHashMap<>();

    /** The constructor Spring uses. With two constructors it has to be told which. */
    @Autowired
    public IcuMessages(ObjectMapper mapper) {
        this(mapper, "i18n/");
    }

    /** For tests: catalogues from another folder of the class path. */
    IcuMessages(ObjectMapper mapper, String folder) {
        for (String language : LANGUAGES) {
            catalogues.put(language, load(mapper, folder + language + ".json"));
        }
    }

    @Override
    public Text text(String id, Locale locale, Object... args) {
        String language = locale == null ? FALLBACK : locale.getLanguage();
        boolean known = catalogues.containsKey(language);
        Map<String, String> catalogue = known ? catalogues.get(language) : catalogues.get(FALLBACK);

        String template = catalogue.get(id);
        boolean fallback = false;

        if (template == null) {
            template = catalogues.get(FALLBACK).get(id);
            fallback = template != null && known;
            if (template == null) {
                log.error("Message id {} is in no catalogue; the id is shown instead", id);
                return new Text(id, true);
            }
        }

        // The language of the text, for plural rules; LATN digits whatever it is (DR-6).
        ULocale forText = ULocale.forLanguageTag((fallback || !known ? FALLBACK : language) + "-LK-u-nu-latn");
        MessageFormat format = new MessageFormat(template, forText);
        // Named arguments come as one Map; positional ones as the array (the catalogues use {0}).
        String value = args != null && args.length == 1 && args[0] instanceof java.util.Map<?, ?> named
                ? format.format(named)
                : format.format(args == null ? new Object[0] : args);
        return new Text(value, fallback);
    }

    private static Map<String, String> load(ObjectMapper mapper, String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Message catalogue " + path + " is not on the class path");
        }
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, new TypeReference<>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Message catalogue " + path + " cannot be read: " + e.getMessage(), e);
        }
    }
}
