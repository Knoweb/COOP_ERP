package lk.coopfed.knoweb.kernel.internal.notification;

import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.util.ULocale;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.Formats;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries;
import lk.coopfed.knoweb.kernel.api.NotificationRuleQueries.NotificationTemplate;
import lk.coopfed.knoweb.kernel.api.SearchSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Renders a notification in the recipient's language with English fallback (doc 19 section
 * 7: "template in the recipient's language with English fallback, through the i18n service").
 * A template of M9 has its texts in three languages, formatted with ICU MessageFormat over the
 * placeholders; a template id the catalogue knows (a message id) renders through
 * {@link Messages} instead, which is what a direct send without M9 uses.
 */
@Component
class NotificationRenderer {

    /** A rendered notification: subject where the channel has one, body, and the language it came out in. */
    record Rendered(String subject, String body, String language, boolean fallback) {}

    /** How a calendar date arrives in an event payload: 2026-09-14. */
    private static final Pattern CALENDAR_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final ObjectProvider<NotificationRuleQueries> rules;
    private final Messages messages;
    private final Formats formats;

    NotificationRenderer(ObjectProvider<NotificationRuleQueries> rules, Messages messages, Formats formats) {
        this.rules = rules;
        this.messages = messages;
        this.formats = formats;
    }

    Rendered render(String templateId, String language, Map<String, Object> arguments) {
        String asked = language == null ? "en" : language.toLowerCase();
        // A language the catalogue does not have (German) is English from the start, not a fallback.
        String requested = SearchSupport.LANGUAGES.contains(asked) ? asked : "en";
        NotificationRuleQueries queries = rules.getIfAvailable();
        Optional<NotificationTemplate> template = queries == null ? Optional.empty() : queries.template(templateId);

        if (template.isPresent()) {
            NotificationTemplate t = template.get();
            String body = pick(requested, t.bodyEn(), t.bodySi(), t.bodyTa());
            boolean fallback = body == null;
            String bodyText = fallback ? t.bodyEn() : body;
            String subject = fallback ? t.subjectEn() : pick(requested, t.subjectEn(), t.subjectSi(), t.subjectTa());
            String used = fallback ? "en" : requested;
            Map<String, Object> shown = withDatesFormatted(arguments);
            return new Rendered(
                    subject == null ? null : format(subject, used, shown),
                    format(bodyText, used, shown),
                    used,
                    fallback);
        }

        // No M9 template: the id is a message of the catalogue.
        Messages.Text text = messages.text(templateId, Locale.forLanguageTag(requested), arguments);
        return new Rendered(null, text.value(), text.fallback() ? "en" : requested, text.fallback());
    }

    private static String pick(String language, String en, String si, String ta) {
        return switch (language) {
            case "si" -> si;
            case "ta" -> ta;
            case "en" -> en;
            default -> null;
        };
    }

    /**
     * A calendar date in the payload (a LocalDate, or its ISO text as an event carries it) is shown
     * dd/MM/yyyy in every language (doc 19 section 5.1), as the screen and the PDF show it. A
     * template cannot do this itself: the value is text by the time MessageFormat sees it, and a
     * mail that said "due 2026-10-29" was found in the demo walkthrough of 29 September 2026.
     */
    private Map<String, Object> withDatesFormatted(Map<String, Object> arguments) {
        if (arguments == null) {
            return Map.of();
        }
        Map<String, Object> shown = new LinkedHashMap<>(arguments);
        shown.replaceAll((name, value) -> {
            if (value instanceof LocalDate date) {
                return formats.date(date);
            }
            if (value instanceof String text && CALENDAR_DATE.matcher(text).matches()) {
                try {
                    return formats.date(LocalDate.parse(text));
                } catch (java.time.format.DateTimeParseException notADate) {
                    return text;
                }
            }
            return value;
        });
        return shown;
    }

    private static String format(String template, String language, Map<String, Object> arguments) {
        return new MessageFormat(template, ULocale.forLanguageTag(language + "-LK-u-nu-latn"))
                .format(arguments == null ? Map.of() : arguments);
    }
}
