package lk.coopfed.knoweb.kernel.internal.stub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.coopfed.knoweb.kernel.api.Messages;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 17A stub of the message catalogue: i18n/{en,si,ta}.json, java.text.MessageFormat, arguments
 * by position ({0}, {1}). 19A K-06 replaces it with ICU MessageFormat and the fallback marker.
 *
 * <p>The three files are read when the application starts. A missing or broken file stops the
 * start: answering every Sinhala user with message ids because of one stray comma in si.json
 * is the kind of fault nobody notices until a shop calls.
 *
 * <p>A language the catalogue does not have (a browser set to German), and an id one language
 * lacks, fall back to English. An id no language has comes back as the id itself, which
 * tools/check-i18n.mjs makes impossible for ids written in the code.
 */
@Component
public class JsonMessages implements Messages {

    static final List<String> LANGUAGES = List.of("en", "si", "ta");
    private static final String FALLBACK = "en";

    private final Map<String, Map<String, String>> catalogues = new LinkedHashMap<>();

    public JsonMessages(ObjectMapper mapper) {
        this(mapper, "i18n/");
    }

    /** For tests: catalogues from another folder of the class path. */
    JsonMessages(ObjectMapper mapper, String folder) {
        for (String language : LANGUAGES) {
            catalogues.put(language, load(mapper, folder + language + ".json"));
        }
    }

    @Override
    public String t(
            String id,
            Locale locale,
            Object... args) {
        String language = locale == null ? FALLBACK : locale.getLanguage();
        Map<String, String> catalogue = catalogues.getOrDefault(language, catalogues.get(FALLBACK));

        String template = catalogue.get(id);
        if (template == null) {
            template = catalogues.get(FALLBACK).getOrDefault(id, id);
        }
        return new MessageFormat(template, locale == null ? Locale.ENGLISH : locale)
                .format(args == null ? new Object[0] : args);
    }

    private static Map<String, String> load(ObjectMapper mapper, String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Message catalogue " + path + " is not on the class path");
        }
        try (InputStream in = resource.getInputStream()) {
            return mapper.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Message catalogue " + path + " cannot be read: " + e.getMessage(), e);
        }
    }
}
