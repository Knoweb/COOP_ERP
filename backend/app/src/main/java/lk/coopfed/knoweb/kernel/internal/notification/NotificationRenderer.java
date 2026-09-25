package lk.coopfed.knoweb.kernel.internal.notification;

import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.util.ULocale;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

    private final ObjectProvider<NotificationRuleQueries> rules;
    private final Messages messages;

    NotificationRenderer(ObjectProvider<NotificationRuleQueries> rules, Messages messages) {
        this.rules = rules;
        this.messages = messages;
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
            return new Rendered(
                    subject == null ? null : format(subject, used, arguments),
                    format(bodyText, used, arguments),
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

    private static String format(String template, String language, Map<String, Object> arguments) {
        return new MessageFormat(template, ULocale.forLanguageTag(language + "-LK-u-nu-latn"))
                .format(arguments == null ? Map.of() : arguments);
    }
}
