// The message catalogues of the web client: the shell's own ids plus one file per module.
// A module adds its `<module>.messages.json` to MODULE_CATALOGUES below and nothing else.
// Every id needs all three languages; a missing one shows the English text with the EN tag.

import helloMessages from "../../modules/hello/hello.messages.json";
// new-module:import (make new-module adds a line above this one; keep the comment)

export type Locale = "en" | "si" | "ta";

type Catalogue = Record<Locale, Record<string, string>>;

const SHELL_MESSAGES: Catalogue = {
  en: {
    "hello_world": "Hello World",
    "shell.lang_fallback": "Not translated yet; shown in English",
    "shell.auth.signing_in": "Signing you in …",
    "shell.auth.failed": "Sign-in did not work",
    "shell.auth.try_again": "Try again",
    "shell.auth.sign_out": "Sign out"
  },
  si: {
    "hello_world": "ආයුබෝවන් ලෝකය",
    "shell.lang_fallback": "තවම පරිවර්තනය කර නැත; ඉංග්‍රීසියෙන් පෙන්වයි",
    "shell.auth.signing_in": "ඔබව පුරනය කරමින් …",
    "shell.auth.failed": "පුරනය වීම සාර්ථක නොවීය",
    "shell.auth.try_again": "නැවත උත්සාහ කරන්න",
    "shell.auth.sign_out": "පිටවන්න"
  },
  ta: {
    "hello_world": "வணக்கம் உலகம்",
    "shell.lang_fallback": "இன்னும் மொழிபெயர்க்கப்படவில்லை; ஆங்கிலத்தில் காட்டப்படுகிறது",
    "shell.auth.signing_in": "உங்களை உள்நுழைக்கிறது …",
    "shell.auth.failed": "உள்நுழைவு வெற்றியடையவில்லை",
    "shell.auth.try_again": "மீண்டும் முயற்சிக்கவும்",
    "shell.auth.sign_out": "வெளியேறு"
  }
};

const MODULE_CATALOGUES: Catalogue[] = [
  helloMessages,
  // new-module:entry (make new-module adds a line above this one; keep the comment)
];

function merge(locale: Locale): Record<string, string> {
  return Object.assign({}, SHELL_MESSAGES[locale], ...MODULE_CATALOGUES.map((c) => c[locale]));
}

export const messages: Record<Locale, Record<string, string>> = {
  en: merge("en"),
  si: merge("si"),
  ta: merge("ta")
};

/**
 * The language of the screen: the user's own (the `lang` claim of the token) when there is
 * one; otherwise the one named in the address (?lang=si), which is all there is before login
 * and what a developer uses to look at a translation; otherwise English.
 */
export function chooseLocale(userLanguage: Locale | null, search: string): Locale {
  if (userLanguage) {
    return userLanguage;
  }
  const requested = new URLSearchParams(search).get("lang");
  return requested === "si" || requested === "ta" ? requested : "en";
}
