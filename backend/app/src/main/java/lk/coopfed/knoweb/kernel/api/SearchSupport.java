package lk.coopfed.knoweb.kernel.api;

import com.ibm.icu.text.Collator;
import com.ibm.icu.util.ULocale;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Collation and search helpers (doc 19 section 5.4; 19A section 6). Name columns come in
 * three, {@code <name>_en}, {@code <name>_si}, {@code <name>_ta}, declared with the ICU
 * collations of the baseline ({@code kernel.en_icu}, {@code kernel.si_icu},
 * {@code kernel.ta_icu}); a list is ordered by the display language's column and collation
 * (P-04, P-09), and a search matches all three columns at once, because the cashier may type
 * in any script.
 */
public final class SearchSupport {

    private SearchSupport() {}

    public static final List<String> LANGUAGES = List.of("en", "si", "ta");

    /** The language suffix of a name column for this locale: en, si or ta; English for any other. */
    public static String language(Locale locale) {
        String language = locale == null ? "en" : locale.getLanguage();
        return LANGUAGES.contains(language) ? language : "en";
    }

    /** {@code kernel.si_icu} and so on: the collation the display language sorts by. */
    public static String collation(Locale locale) {
        return "kernel." + language(locale) + "_icu";
    }

    /**
     * An ORDER BY fragment on the display language's name column: the translated rows first
     * in the language's collation, then the rows that lack the translation, in English:
     * {@code (legal_name_si IS NULL), legal_name_si COLLATE kernel.si_icu, legal_name_en COLLATE kernel.en_icu}.
     * The null test comes first on purpose: a {@code coalesce} into the Sinhala collation would
     * put the Latin fallbacks before the Sinhala names, not after them.
     *
     * @param nameColumn the column stem, for example {@code legal_name}
     */
    public static String orderByDisplayLanguage(String nameColumn, Locale locale) {
        requireIdentifier(nameColumn);
        String language = language(locale);
        if ("en".equals(language)) {
            return nameColumn + "_en COLLATE kernel.en_icu";
        }
        String translated = nameColumn + "_" + language;
        return "(" + translated + " IS NULL), " + translated + " COLLATE kernel." + language + "_icu, " + nameColumn
                + "_en COLLATE kernel.en_icu";
    }

    /**
     * The same order in Java, for a list sorted in memory (a snapshot, a report): an ICU
     * collator of the display language, so the backend and PostgreSQL agree on the order of
     * the sort test set. Not thread-safe; make one per sort.
     */
    public static Comparator<String> comparator(Locale locale) {
        Collator collator = Collator.getInstance(ULocale.forLanguageTag(language(locale) + "-LK"));
        return Comparator.nullsLast(collator::compare);
    }

    /**
     * A WHERE fragment that matches a typed prefix or fragment against all three name columns
     * with one bound parameter, for the trigram indexes of the migration conventions:
     * {@code (legal_name_en ILIKE ? OR legal_name_si ILIKE ? OR legal_name_ta ILIKE ?)}. Bind
     * {@link #pattern} three times.
     */
    public static String matchAnyLanguage(String nameColumn) {
        requireIdentifier(nameColumn);
        return "(" + nameColumn + "_en ILIKE ? OR " + nameColumn + "_si ILIKE ? OR " + nameColumn + "_ta ILIKE ?)";
    }

    /** What the user typed as an ILIKE pattern: NFC, the LIKE wild cards escaped, a fragment anywhere in the name. */
    public static String pattern(String typed) {
        String text = TextNormaliser.nfc(typed == null ? "" : typed);
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static void requireIdentifier(String column) {
        if (column == null || !column.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("A column stem is a plain snake_case identifier: " + column);
        }
    }
}
