// The message catalogues of the web client: the shell's own ids plus one file per module.
// A module adds its `<module>.messages.json` to MODULE_CATALOGUES below and nothing else.
// Every id needs all three languages; a missing one shows the English text with the EN tag.

// `with { type: "json" }` is the standard import attribute for a JSON module. The bundler does
// not need it, but the Playwright tests (web/e2e) import this file straight into Node, which
// refuses a JSON import without it. Keep it on every catalogue line below.
import helloMessages from "../../modules/hello/hello.messages.json" with { type: "json" };
// new-module:import (make new-module adds a line above this one; keep the comment)

export type Locale = "en" | "si" | "ta";

type Catalogue = Record<Locale, Record<string, string>>;

const SHELL_MESSAGES: Catalogue = {
  en: {
    "shell.lang_fallback": "Not translated yet; shown in English",
    "shell.auth.signing_in": "Signing you in …",
    "shell.auth.failed": "Sign-in did not work",
    "shell.auth.try_again": "Try again",
    "shell.auth.sign_out": "Sign out",
    "shell.scope.label": "Your scope",
    "shell.scope.acting_for": "{user} is acting for {entity}",
    "shell.scope.entity_unnamed": "Entity …{shortId}",
    "shell.scope.location_all": "All locations",
    "shell.scope.access": "Access:",
    "shell.scope.class.OWN": "Own entity",
    "shell.scope.class.PARTY": "Trading partner (documents shared with you)",
    "shell.scope.class.FEDERATION_VIEW": "Federation view (sees every entity)",
    "shell.scope.class.EXTERNAL_TIMEBOXED": "External access for a limited time",
    "shell.scope.class.NONE": "No access",
    "shell.scope.none.title": "Warning: you have no scope.",
    "shell.scope.none.text": "You are not assigned to any entity, so you will see no data. Ask your administrator.",
    "shell.nav.label": "Modules",
    "shell.nav.empty": "You have no permission for any module. Ask your administrator.",
    "shell.not_allowed.title": "You are not allowed to open this page",
    "shell.not_allowed.text": "The page exists, but your role does not include it. Ask your administrator if you need it.",
    "shell.not_found.title": "Page not found",
    "shell.not_found.text": "Nothing lives at this address. Choose a module from the navigation.",
    "shell.home.title": "Cooperative back office",
    "shell.home.text": "Choose a module from the navigation.",
    "shell.money.amount": "Rs {amount}",
    "shell.money.negative": "−Rs {amount}",
    "shell.money.negative_spoken": "minus Rs {amount}",
    "shell.money.invalid": "Amount not available",
    "shell.state.draft": "Draft",
    "shell.state.issued": "Issued",
    "shell.state.disputed": "Disputed",
    "shell.state.void": "Void",
    "shell.state.alert": "Alert",
    "shell.training.title": "TRAINING MODE",
    "shell.training.text": "This is practice. Nothing you do here is real, and no document made here counts.",
    "shell.design.title": "Design reference",
    "shell.design.intro": "What the shell gives every module: the design tokens and the shared components. A module uses these and writes no colour and no size of its own.",
    "shell.design.sample": "Cooperative society 1,234.00",
    "shell.design.type.title": "Type scale",
    "shell.design.space.title": "Spacing on the 8 px grid",
    "shell.design.colour.title": "Colours and their contrast",
    "shell.design.colour.text": "Measured by the WCAG 2.2 formula from the tokens in use. The build fails when a pair falls below what it needs.",
    "shell.design.components.title": "Shared components",
    "shell.design.components.chip_own_label": "Goods received",
    "shell.design.components.training_off": "Outside training mode the badge shows nothing.",
    "shell.design.col.token": "Token",
    "shell.design.col.value": "Value",
    "shell.design.col.example": "Example",
    "shell.design.col.contrast": "Contrast",
    "shell.design.col.required": "Needs at least",
    "shell.design.col.input": "What the server sent",
    "shell.design.col.shown": "What the screen shows"
  },
  si: {
    "shell.lang_fallback": "තවම පරිවර්තනය කර නැත; ඉංග්‍රීසියෙන් පෙන්වයි",
    "shell.auth.signing_in": "ඔබව පුරනය කරමින් …",
    "shell.auth.failed": "පුරනය වීම සාර්ථක නොවීය",
    "shell.auth.try_again": "නැවත උත්සාහ කරන්න",
    "shell.auth.sign_out": "පිටවන්න",
    "shell.scope.label": "ඔබේ විෂය පථය",
    "shell.scope.acting_for": "{user} ක්‍රියා කරන්නේ {entity} වෙනුවෙනි",
    "shell.scope.entity_unnamed": "ආයතනය …{shortId}",
    "shell.scope.location_all": "සියලු ස්ථාන",
    "shell.scope.access": "ප්‍රවේශය:",
    "shell.scope.class.OWN": "තමන්ගේ ආයතනය",
    "shell.scope.class.PARTY": "වෙළඳ හවුල්කරු (ඔබ සමඟ බෙදාගත් ලේඛන)",
    "shell.scope.class.FEDERATION_VIEW": "සම්මේලන දර්ශනය (සියලු ආයතන පෙනේ)",
    "shell.scope.class.EXTERNAL_TIMEBOXED": "සීමිත කාලයකට බාහිර ප්‍රවේශය",
    "shell.scope.class.NONE": "ප්‍රවේශයක් නැත",
    "shell.scope.none.title": "අවවාදයයි: ඔබට විෂය පථයක් නැත.",
    "shell.scope.none.text": "ඔබ කිසිදු ආයතනයකට පවරා නැති බැවින් ඔබට කිසිදු දත්තයක් නොපෙනේ. ඔබේ පරිපාලකයාගෙන් විමසන්න.",
    "shell.nav.label": "මොඩියුල",
    "shell.nav.empty": "ඔබට කිසිදු මොඩියුලයකට අවසර නැත. ඔබේ පරිපාලකයාගෙන් විමසන්න.",
    "shell.not_allowed.title": "මෙම පිටුව විවෘත කිරීමට ඔබට අවසර නැත",
    "shell.not_allowed.text": "පිටුව පවතී, නමුත් ඔබේ භූමිකාවට එය ඇතුළත් නැත. අවශ්‍ය නම් ඔබේ පරිපාලකයාගෙන් විමසන්න.",
    "shell.not_found.title": "පිටුව හමු නොවීය",
    "shell.not_found.text": "මෙම ලිපිනයේ කිසිවක් නැත. සංචාලනයෙන් මොඩියුලයක් තෝරන්න.",
    "shell.home.title": "සමුපකාර පසුබිම් කාර්යාලය",
    "shell.home.text": "සංචාලනයෙන් මොඩියුලයක් තෝරන්න.",
    "shell.money.amount": "රු. {amount}",
    "shell.money.negative": "−රු. {amount}",
    "shell.money.negative_spoken": "ඍණ රු. {amount}",
    "shell.money.invalid": "මුදල ලබාගත නොහැක",
    "shell.state.draft": "කෙටුම්පත",
    "shell.state.issued": "නිකුත් කළ",
    "shell.state.disputed": "විවාදිත",
    "shell.state.void": "අවලංගු",
    "shell.state.alert": "අනතුරු ඇඟවීම",
    "shell.training.title": "පුහුණු ප්‍රකාරය",
    "shell.training.text": "මෙය පුහුණුවකි. ඔබ මෙහි කරන කිසිවක් සැබෑ නොවේ; මෙහි සාදන කිසිදු ලේඛනයක් ගණන් නොගැනේ.",
    "shell.design.title": "නිර්මාණ යොමුව",
    "shell.design.intro": "ෂෙල් එක සෑම මොඩියුලයකටම දෙන දේ: නිර්මාණ ටෝකන සහ පොදු සංරචක. මොඩියුලයක් මේවා භාවිත කරයි; තමන්ගේම වර්ණයක් හෝ ප්‍රමාණයක් නොලියයි.",
    "shell.design.sample": "සමුපකාර සමිතිය 1,234.00",
    "shell.design.type.title": "අකුරු ප්‍රමාණ",
    "shell.design.space.title": "8 px ජාලකයේ පරතර",
    "shell.design.colour.title": "වර්ණ සහ ඒවායේ ප්‍රතිවිරෝධය",
    "shell.design.colour.text": "භාවිතයේ ඇති ටෝකන වලින් WCAG 2.2 සූත්‍රය අනුව මනිනු ලැබේ. යුගලයක් අවශ්‍ය අගයට වඩා අඩු වූ විට ගොඩනැගීම අසාර්ථක වේ.",
    "shell.design.components.title": "පොදු සංරචක",
    "shell.design.components.chip_own_label": "භාණ්ඩ ලැබුණි",
    "shell.design.components.training_off": "පුහුණු ප්‍රකාරයෙන් පිටත මෙම ලාංඡනය කිසිවක් නොපෙන්වයි.",
    "shell.design.col.token": "ටෝකනය",
    "shell.design.col.value": "අගය",
    "shell.design.col.example": "උදාහරණය",
    "shell.design.col.contrast": "ප්‍රතිවිරෝධය",
    "shell.design.col.required": "අවම අවශ්‍යතාව",
    "shell.design.col.input": "සේවාදායකය එවූ දේ",
    "shell.design.col.shown": "තිරයේ පෙන්වන දේ"
  },
  ta: {
    "shell.lang_fallback": "இன்னும் மொழிபெயர்க்கப்படவில்லை; ஆங்கிலத்தில் காட்டப்படுகிறது",
    "shell.auth.signing_in": "உங்களை உள்நுழைக்கிறது …",
    "shell.auth.failed": "உள்நுழைவு வெற்றியடையவில்லை",
    "shell.auth.try_again": "மீண்டும் முயற்சிக்கவும்",
    "shell.auth.sign_out": "வெளியேறு",
    "shell.scope.label": "உங்கள் செயல்பாட்டு எல்லை",
    "shell.scope.acting_for": "{user} செயல்படுவது {entity} சார்பாக",
    "shell.scope.entity_unnamed": "நிறுவனம் …{shortId}",
    "shell.scope.location_all": "அனைத்து இடங்கள்",
    "shell.scope.access": "அணுகல்:",
    "shell.scope.class.OWN": "சொந்த நிறுவனம்",
    "shell.scope.class.PARTY": "வர்த்தகப் பங்காளர் (உங்களுடன் பகிரப்பட்ட ஆவணங்கள்)",
    "shell.scope.class.FEDERATION_VIEW": "சம்மேளனப் பார்வை (அனைத்து நிறுவனங்களும் தெரியும்)",
    "shell.scope.class.EXTERNAL_TIMEBOXED": "வரையறுக்கப்பட்ட காலத்திற்கான வெளி அணுகல்",
    "shell.scope.class.NONE": "அணுகல் இல்லை",
    "shell.scope.none.title": "எச்சரிக்கை: உங்களுக்கு செயல்பாட்டு எல்லை இல்லை.",
    "shell.scope.none.text": "நீங்கள் எந்த நிறுவனத்திற்கும் ஒதுக்கப்படவில்லை, எனவே எந்தத் தரவும் உங்களுக்குத் தெரியாது. உங்கள் நிர்வாகியிடம் கேளுங்கள்.",
    "shell.nav.label": "தொகுதிகள்",
    "shell.nav.empty": "எந்தத் தொகுதிக்கும் உங்களுக்கு அனுமதி இல்லை. உங்கள் நிர்வாகியிடம் கேளுங்கள்.",
    "shell.not_allowed.title": "இந்தப் பக்கத்தைத் திறக்க உங்களுக்கு அனுமதி இல்லை",
    "shell.not_allowed.text": "பக்கம் உள்ளது, ஆனால் உங்கள் பங்கில் அது சேர்க்கப்படவில்லை. தேவைப்பட்டால் உங்கள் நிர்வாகியிடம் கேளுங்கள்.",
    "shell.not_found.title": "பக்கம் கிடைக்கவில்லை",
    "shell.not_found.text": "இந்த முகவரியில் எதுவும் இல்லை. வழிசெலுத்தலில் இருந்து ஒரு தொகுதியைத் தேர்ந்தெடுக்கவும்.",
    "shell.home.title": "கூட்டுறவு பின் அலுவலகம்",
    "shell.home.text": "வழிசெலுத்தலில் இருந்து ஒரு தொகுதியைத் தேர்ந்தெடுக்கவும்.",
    "shell.money.amount": "ரூ. {amount}",
    "shell.money.negative": "−ரூ. {amount}",
    "shell.money.negative_spoken": "கழித்தல் ரூ. {amount}",
    "shell.money.invalid": "தொகை கிடைக்கவில்லை",
    "shell.state.draft": "வரைவு",
    "shell.state.issued": "வழங்கப்பட்டது",
    "shell.state.disputed": "சர்ச்சைக்குரியது",
    "shell.state.void": "ரத்து",
    "shell.state.alert": "எச்சரிக்கை",
    "shell.training.title": "பயிற்சி முறை",
    "shell.training.text": "இது பயிற்சி. இங்கு நீங்கள் செய்வது எதுவும் உண்மையானது அல்ல; இங்கு உருவாக்கப்படும் எந்த ஆவணமும் கணக்கில் சேராது.",
    "shell.design.title": "வடிவமைப்புக் குறிப்பு",
    "shell.design.intro": "ஷெல் ஒவ்வொரு தொகுதிக்கும் வழங்குவது: வடிவமைப்பு டோக்கன்கள் மற்றும் பொதுக் கூறுகள். ஒரு தொகுதி இவற்றைப் பயன்படுத்துகிறது; தனக்கென எந்த நிறத்தையும் அளவையும் எழுதுவதில்லை.",
    "shell.design.sample": "கூட்டுறவுச் சங்கம் 1,234.00",
    "shell.design.type.title": "எழுத்து அளவுகள்",
    "shell.design.space.title": "8 px கட்டத்தில் இடைவெளிகள்",
    "shell.design.colour.title": "நிறங்களும் அவற்றின் மாறுபாடும்",
    "shell.design.colour.text": "பயன்பாட்டில் உள்ள டோக்கன்களிலிருந்து WCAG 2.2 சூத்திரத்தின்படி அளவிடப்படுகிறது. ஒரு இணை தேவையான அளவுக்குக் கீழே சென்றால் உருவாக்கம் தோல்வியடையும்.",
    "shell.design.components.title": "பொதுக் கூறுகள்",
    "shell.design.components.chip_own_label": "பொருட்கள் பெறப்பட்டன",
    "shell.design.components.training_off": "பயிற்சி முறைக்கு வெளியே இந்தப் பட்டை எதையும் காட்டாது.",
    "shell.design.col.token": "டோக்கன்",
    "shell.design.col.value": "மதிப்பு",
    "shell.design.col.example": "எடுத்துக்காட்டு",
    "shell.design.col.contrast": "மாறுபாடு",
    "shell.design.col.required": "குறைந்தபட்சத் தேவை",
    "shell.design.col.input": "சேவையகம் அனுப்பியது",
    "shell.design.col.shown": "திரை காட்டுவது"
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
