package lk.coopfed.knoweb.kernel.api;

import java.util.Locale;

/**
 * The message catalogue (doc 19 section 5.2; 19A section 6): every user-visible string is a
 * stable message id with an English, a Sinhala and a Tamil text, formatted with ICU
 * MessageFormat (plurals, placeholders by position {0} or by name {count}).
 *
 * <p>Fallback is field-level and never silent: an id the requested language lacks comes back
 * in English with {@link Text#fallback()} set, and the web shell shows its EN mark. An id no
 * language has is a build error (tools/check-i18n.mjs); at run time it comes back as the id
 * and is logged, never rendered blank.
 */
public interface Messages {

    /** A translated text and whether it is the English fallback rather than the requested language. */
    record Text(String value, boolean fallback) {}

    /** The text, with the fallback flag. */
    Text text(String id, Locale locale, Object... args);

    /** The text alone, for callers that show it as it is. */
    default String t(String id, Locale locale, Object... args) {
        return text(id, locale, args).value();
    }
}
