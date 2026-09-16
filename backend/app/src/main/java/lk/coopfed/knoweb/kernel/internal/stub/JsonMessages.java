package lk.coopfed.knoweb.kernel.internal.stub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.coopfed.knoweb.kernel.api.Messages;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JsonMessages implements Messages {

    private final ObjectMapper mapper;
    private final Map<String, Map<String, String>> catalogues = new ConcurrentHashMap<>();

    public JsonMessages(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String t(
            String id,
            Locale locale,
            Object... args) {
        String language = locale == null
                ? "en"
                : locale.getLanguage();

        Map<String, String> catalogue = catalogues.computeIfAbsent(
                language,
                this::load);

        String template = catalogue.getOrDefault(
                id,
                id);

        return new MessageFormat(
                template,
                locale == null
                        ? Locale.ENGLISH
                        : locale)
                .format(args == null ? new Object[0] : args);
    }

    private Map<String, String> load(
            String language) {
        try {
            ClassPathResource resource = new ClassPathResource(
                    "i18n/" + language + ".json");

            if (!resource.exists()) {
                return Map.of();
            }

            return mapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<>() {
                    });
        } catch (Exception e) {
            return Map.of();
        }
    }
}