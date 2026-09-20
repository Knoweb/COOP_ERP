import { useIntl } from "react-intl";

/**
 * The small "EN" mark shown next to a text that has no translation in the user's language
 * and is therefore displayed in English (doc 30 section 2.1). It tells the user, and the
 * data steward, that a translation is missing; the screen never hides the text instead.
 */
export function LangFallbackTag() {
  const intl = useIntl();
  return (
    <span
      className="lang-fallback-tag"
      title={intl.formatMessage({ id: "shell.lang_fallback" })}
    >
      EN
    </span>
  );
}
