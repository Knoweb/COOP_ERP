// The message catalogues of the web client: the shell's own ids plus one file per module.
// A module adds its `<module>.messages.json` to MODULE_CATALOGUES below and nothing else.
// Every id needs all three languages; a missing one shows the English text with the EN tag.

import helloMessages from "../../modules/hello/hello.messages.json";

export type Locale = "en" | "si" | "ta";

type Catalogue = Record<Locale, Record<string, string>>;

const SHELL_MESSAGES: Catalogue = {
  en: {
    "hello_world": "Hello World",
    "shell.lang_fallback": "Not translated yet; shown in English"
  },
  si: {
    "hello_world": "ආයුබෝවන් ලෝකය",
    "shell.lang_fallback": "තවම පරිවර්තනය කර නැත; ඉංග්‍රීසියෙන් පෙන්වයි"
  },
  ta: {
    "hello_world": "வணக்கம் உலகம்",
    "shell.lang_fallback": "இன்னும் மொழிபெயர்க்கப்படவில்லை; ஆங்கிலத்தில் காட்டப்படுகிறது"
  }
};

const MODULE_CATALOGUES: Catalogue[] = [helloMessages];

function merge(locale: Locale): Record<string, string> {
  return Object.assign({}, SHELL_MESSAGES[locale], ...MODULE_CATALOGUES.map((c) => c[locale]));
}

export const messages: Record<Locale, Record<string, string>> = {
  en: merge("en"),
  si: merge("si"),
  ta: merge("ta")
};

/** The language named in the address (?lang=si), or English. */
export function initialLocale(): Locale {
  const requested = new URLSearchParams(window.location.search).get("lang");
  return requested === "si" || requested === "ta" ? requested : "en";
}
