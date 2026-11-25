package lk.coopfed.knoweb.m2catalogue.internal.steward;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * High-performance transliterator for Sri Lankan trilingual catalogue items (CAT-01, 22A section 6.1).
 *
 * <p>Handles:
 * <ul>
 *   <li>Transliterating Sinhala and Tamil names to normalized Latin keys for duplicate scoring and search.</li>
 *   <li>Generating initial Sinhala ({@code si}) and Tamil ({@code ta}) translations when only English is provided.</li>
 *   <li>Strict Unicode NFC normalization across all operations.</li>
 * </ul>
 */
@Component
public class Transliterator {

    public record TransliterationResult(String nameEn, String nameSi, String nameTa) {}

    // Common Sri Lankan retail product dictionary: English -> Sinhala / Tamil
    private static final Map<String, String> EN_TO_SI_TERMS = new HashMap<>();
    private static final Map<String, String> EN_TO_TA_TERMS = new HashMap<>();

    static {
        // Essential grocery & retail terms
        addTerm("milk", "කිරි", "பால்");
        addTerm("powder", "පිටි", "மா");
        addTerm("rice", "සහල්", "அரிசி");
        addTerm("sugar", "සීනි", "சர்க்கரை");
        addTerm("tea", "තේ", "தேநீர்");
        addTerm("coffee", "කෝපි", "காபி");
        addTerm("dhal", "පරිප්පු", "பருப்பு");
        addTerm("oil", "තෙල්", "எண்ணெய்");
        addTerm("soap", "සබන්", "சோப்பு");
        addTerm("flour", "පිටි", "மாவு");
        addTerm("salt", "ලුණු", "உப்பு");
        addTerm("biscuit", "බිස්කට්", "பிஸ்கட்");
        addTerm("biscuits", "බිස්කට්", "பிஸ்கட்");
        addTerm("water", "වතුර", "தண்ணீர்");
        addTerm("chilli", "මිරිස්", "மிளகாய்");
        addTerm("onion", "ලූනු", "வெங்காயம்");
        addTerm("potato", "අල", "உருளைக்கிழங்கு");
        addTerm("fish", "මාළු", "மீன்");
        addTerm("chicken", "කුකුළු මස්", "கோழி");
        addTerm("noodles", "නූඩ්ල්ස්", "நூடுல்ஸ்");
        addTerm("bread", "පාන්", "ரொட்டி");
        addTerm("butter", "බටර්", "வெண்ணெய்");
        addTerm("cheese", "චීස්", "சீஸ்");
        addTerm("egg", "බිත්තර", "முட்டை");
        addTerm("eggs", "බිත්තර", "முட்டைகள்");
        addTerm("toothpaste", "දන්තාලේප", "பற்பசை");
        addTerm("washing", "රෙදි සෝදන", "சலவை");
        addTerm("red", "රතු", "சிவப்பு");
        addTerm("white", "සුදු", "வெள்ளை");
        addTerm("samba", "සම්බා", "சம்பா");
        addTerm("keeri", "කීරි", "கீறி");
        addTerm("nadu", "නාඩු", "நாடு");

        // Common Sri Lankan household FMCG brands
        addTerm("anchor", "ඇන්කර්", "அங்கர்");
        addTerm("maliban", "මැලිබන්", "மலிபன்");
        addTerm("munchee", "මංචි", "மஞ்சி");
        addTerm("cargills", "කාර්ගිල්ස්", "கார்கில்ஸ்");
        addTerm("keells", "කීල්ස්", "கீல்ஸ்");
        addTerm("kotmale", "කොත්මලේ", "கொத்மலை");
        addTerm("pelwatte", "පැල්වත්ත", "பெல்வத்த");
        addTerm("nestle", "නෙස්ලේ", "நெஸ்லே");
        addTerm("sunlight", "සන්ලයිට්", "சன்லைட்");
        addTerm("signal", "සිග්නල්", "சிக்னல்");
        addTerm("dilmah", "දිල්මා", "டில்மா");
        addTerm("knorr", "නෝර්", "நோர்");
        addTerm("maggi", "මැගී", "மேகி");
        addTerm("harischandra", "හරිශ්චන්ද්‍ර", "ஹரிஸ்சந்திர");
        addTerm("wijaya", "විජය", "விஜய");
        addTerm("md", "එම්ඩී", "எம்டி");
        addTerm("raththi", "රත්ති", "ரத்தி");
        addTerm("highland", "හයිලන්ඩ්", "ஹைலண்ட்");
    }

    private static void addTerm(String en, String si, String ta) {
        EN_TO_SI_TERMS.put(en.toLowerCase(Locale.ROOT), si);
        EN_TO_TA_TERMS.put(en.toLowerCase(Locale.ROOT), ta);
    }

    /**
     * Completes missing Sinhala and Tamil item names from English.
     */
    public TransliterationResult transliterateMissing(String nameEn, String nameSi, String nameTa) {
        String en = nfc(nameEn);
        String si = nfc(nameSi);
        String ta = nfc(nameTa);

        if (en != null && !en.isBlank()) {
            if (si == null || si.isBlank()) {
                si = toSinhala(en);
            }
            if (ta == null || ta.isBlank()) {
                ta = toTamil(en);
            }
        }

        return new TransliterationResult(en, si, ta);
    }

    /**
     * Transliterates English/Latin text to Sinhala representation.
     */
    public String toSinhala(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] tokens = text.trim().split("\\s+");
        List<String> result = new ArrayList<>();

        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (EN_TO_SI_TERMS.containsKey(lower)) {
                result.add(EN_TO_SI_TERMS.get(lower));
            } else if (token.matches(".*\\d+.*")) {
                result.add(token); // preserve numbers/quantities like 400g, 1kg
            } else {
                result.add(phoneticLatinToSinhala(token));
            }
        }

        return Normalizer.normalize(String.join(" ", result), Normalizer.Form.NFC);
    }

    /**
     * Transliterates English/Latin text to Tamil representation.
     */
    public String toTamil(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] tokens = text.trim().split("\\s+");
        List<String> result = new ArrayList<>();

        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (EN_TO_TA_TERMS.containsKey(lower)) {
                result.add(EN_TO_TA_TERMS.get(lower));
            } else if (token.matches(".*\\d+.*")) {
                result.add(token); // preserve numbers/quantities
            } else {
                result.add(phoneticLatinToTamil(token));
            }
        }

        return Normalizer.normalize(String.join(" ", result), Normalizer.Form.NFC);
    }

    /**
     * Transliterates Sinhala and Tamil characters to a normalized Latin key for duplicate scoring.
     */
    public String toLatin(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < nfc.length(); i++) {
            char c = nfc.charAt(i);

            // Sinhala Unicode range: 0x0D80 to 0x0DFF
            if (c >= 0x0D80 && c <= 0x0DFF) {
                sb.append(sinhalaCharToLatin(c, nfc, i));
            }
            // Tamil Unicode range: 0x0B80 to 0x0BFF
            else if (c >= 0x0B80 && c <= 0x0BFF) {
                sb.append(tamilCharToLatin(c, nfc, i));
            } else {
                sb.append(c);
            }
        }

        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC);
    }

    private static String nfc(String s) {
        return s == null ? null : Normalizer.normalize(s.trim(), Normalizer.Form.NFC);
    }

    // --- Sinhala to Latin mapping ---

    private String sinhalaCharToLatin(char c, String str, int index) {
        return switch (c) {
            // Independent Vowels
            case 0x0D85 -> "a";
            case 0x0D86 -> "aa";
            case 0x0D87 -> "ae";
            case 0x0D88 -> "aae";
            case 0x0D89 -> "i";
            case 0x0D8A -> "ee";
            case 0x0D8B -> "u";
            case 0x0D8C -> "oo";
            case 0x0D8F -> "e";
            case 0x0D90 -> "ee";
            case 0x0D91 -> "ai";
            case 0x0D92 -> "o";
            case 0x0D93 -> "oo";
            case 0x0D94 -> "au";

            // Consonants (default inherent 'a' handled unless followed by hal kireema or vowel sign)
            case 0x0D9A -> consonant("k", str, index);
            case 0x0D9B -> consonant("kh", str, index);
            case 0x0D9C -> consonant("g", str, index);
            case 0x0D9D -> consonant("gh", str, index);
            case 0x0D9E, 0x0D9F -> consonant("ng", str, index);
            case 0x0DA0 -> consonant("ch", str, index);
            case 0x0DA1 -> consonant("chh", str, index);
            case 0x0DA2 -> consonant("j", str, index);
            case 0x0DA3 -> consonant("jh", str, index);
            case 0x0DA4, 0x0DA5 -> consonant("ny", str, index);
            case 0x0DA7 -> consonant("t", str, index);
            case 0x0DA8 -> consonant("th", str, index);
            case 0x0DA9 -> consonant("d", str, index);
            case 0x0DAA -> consonant("dh", str, index);
            case 0x0DAB -> consonant("n", str, index);
            case 0x0DAC -> consonant("nd", str, index);
            case 0x0DAD -> consonant("t", str, index);
            case 0x0DAE -> consonant("th", str, index);
            case 0x0DAF -> consonant("d", str, index);
            case 0x0DB0 -> consonant("dh", str, index);
            case 0x0DB1 -> consonant("n", str, index);
            case 0x0DB3 -> consonant("nd", str, index);
            case 0x0DB4 -> consonant("p", str, index);
            case 0x0DB5 -> consonant("ph", str, index);
            case 0x0DB6 -> consonant("b", str, index);
            case 0x0DB7 -> consonant("bh", str, index);
            case 0x0DB8 -> consonant("m", str, index);
            case 0x0DB9 -> consonant("mb", str, index);
            case 0x0DBA -> consonant("y", str, index);
            case 0x0DBB -> consonant("r", str, index);
            case 0x0DBD -> consonant("l", str, index);
            case 0x0DC0 -> consonant("v", str, index);
            case 0x0DC1, 0x0DC2 -> consonant("sh", str, index);
            case 0x0DC3 -> consonant("s", str, index);
            case 0x0DC4 -> consonant("h", str, index);
            case 0x0DC5 -> consonant("l", str, index);
            case 0x0DC6 -> consonant("f", str, index);

            // Dependent Vowel Signs
            case 0x0DCF -> "aa";
            case 0x0DD0 -> "ae";
            case 0x0DD1 -> "aae";
            case 0x0DD2 -> "i";
            case 0x0DD3 -> "ee";
            case 0x0DD4 -> "u";
            case 0x0DD6 -> "oo";
            case 0x0DD9 -> "e";
            case 0x0DDA -> "ee";
            case 0x0DDB -> "ai";
            case 0x0DDC -> "o";
            case 0x0DDD -> "oo";
            case 0x0DDE -> "au";
            case 0x0DCA -> ""; // Hal kireema: silences inherent vowel
            case 0x0D82 -> "ng"; // Anusvaraya

            default -> "";
        };
    }

    private static String consonant(String base, String str, int index) {
        if (index + 1 < str.length()) {
            char next = str.charAt(index + 1);
            // If followed by hal kireema or a combining vowel mark, return just the base consonant
            if (next == 0x0DCA || (next >= 0x0DCF && next <= 0x0DDE)) {
                return base;
            }
        }
        // Default inherent vowel
        return base + "a";
    }

    // --- Tamil to Latin mapping ---

    private String tamilCharToLatin(char c, String str, int index) {
        return switch (c) {
            case 0x0B85 -> "a";
            case 0x0B86 -> "aa";
            case 0x0B87 -> "i";
            case 0x0B88 -> "ee";
            case 0x0B89 -> "u";
            case 0x0B8A -> "oo";
            case 0x0B8E -> "e";
            case 0x0B8F -> "ee";
            case 0x0B90 -> "ai";
            case 0x0B92 -> "o";
            case 0x0B93 -> "oo";
            case 0x0B94 -> "au";

            case 0x0B95 -> tamilConsonant("k", str, index);
            case 0x0B99 -> tamilConsonant("ng", str, index);
            case 0x0B9A -> tamilConsonant("s", str, index);
            case 0x0B9E -> tamilConsonant("ny", str, index);
            case 0x0B9F -> tamilConsonant("t", str, index);
            case 0x0BA3 -> tamilConsonant("n", str, index);
            case 0x0BA4 -> tamilConsonant("th", str, index);
            case 0x0BA8, 0x0BA9 -> tamilConsonant("n", str, index);
            case 0x0BAA -> tamilConsonant("p", str, index);
            case 0x0BAE -> tamilConsonant("m", str, index);
            case 0x0BAF -> tamilConsonant("y", str, index);
            case 0x0BB0, 0x0BB1 -> tamilConsonant("r", str, index);
            case 0x0BB2, 0x0BB3 -> tamilConsonant("l", str, index);
            case 0x0BB4 -> tamilConsonant("zh", str, index);
            case 0x0BB5 -> tamilConsonant("v", str, index);
            case 0x0B9C -> tamilConsonant("j", str, index);
            case 0x0BB7 -> tamilConsonant("sh", str, index);
            case 0x0BB8 -> tamilConsonant("s", str, index);
            case 0x0BB9 -> tamilConsonant("h", str, index);

            case 0x0BBE -> "aa";
            case 0x0BBF -> "i";
            case 0x0BC0 -> "ee";
            case 0x0BC1 -> "u";
            case 0x0BC2 -> "oo";
            case 0x0BC6 -> "e";
            case 0x0BC7 -> "ee";
            case 0x0BC8 -> "ai";
            case 0x0BCA -> "o";
            case 0x0BCB -> "oo";
            case 0x0BCC -> "au";
            case 0x0BCD -> ""; // Pulli

            default -> "";
        };
    }

    private static String tamilConsonant(String base, String str, int index) {
        if (index + 1 < str.length()) {
            char next = str.charAt(index + 1);
            if (next == 0x0BCD || (next >= 0x0BBE && next <= 0x0BCC)) {
                return base;
            }
        }
        return base + "a";
    }

    // --- Phonetic Latin to Sinhala / Tamil fallback ---

    private String phoneticLatinToSinhala(String word) {
        StringBuilder sb = new StringBuilder();
        String lower = word.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < lower.length()) {
            if (i + 1 < lower.length()) {
                String sub2 = lower.substring(i, i + 2);
                if (LATIN_TO_SI_DOUBLE.containsKey(sub2)) {
                    sb.append(LATIN_TO_SI_DOUBLE.get(sub2));
                    i += 2;
                    continue;
                }
            }
            String sub1 = lower.substring(i, i + 1);
            sb.append(LATIN_TO_SI_SINGLE.getOrDefault(sub1, sub1));
            i++;
        }
        return sb.toString();
    }

    private String phoneticLatinToTamil(String word) {
        StringBuilder sb = new StringBuilder();
        String lower = word.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < lower.length()) {
            if (i + 1 < lower.length()) {
                String sub2 = lower.substring(i, i + 2);
                if (LATIN_TO_TA_DOUBLE.containsKey(sub2)) {
                    sb.append(LATIN_TO_TA_DOUBLE.get(sub2));
                    i += 2;
                    continue;
                }
            }
            String sub1 = lower.substring(i, i + 1);
            sb.append(LATIN_TO_TA_SINGLE.getOrDefault(sub1, sub1));
            i++;
        }
        return sb.toString();
    }

    private static final Map<String, String> LATIN_TO_SI_DOUBLE = Map.ofEntries(
            Map.entry("ch", "ච"),
            Map.entry("sh", "ෂ"),
            Map.entry("th", "ත"),
            Map.entry("dh", "ද"),
            Map.entry("ph", "ෆ"),
            Map.entry("kh", "ඛ"),
            Map.entry("gh", "ඝ"),
            Map.entry("aa", "ආ"),
            Map.entry("ee", "ඊ"),
            Map.entry("oo", "ඌ"),
            Map.entry("ai", "ඓ"),
            Map.entry("au", "ඖ"));

    private static final Map<String, String> LATIN_TO_SI_SINGLE = Map.ofEntries(
            Map.entry("a", "අ"),
            Map.entry("b", "බ"),
            Map.entry("c", "ක"),
            Map.entry("d", "ඩ"),
            Map.entry("e", "එ"),
            Map.entry("f", "ෆ"),
            Map.entry("g", "ග"),
            Map.entry("h", "හ"),
            Map.entry("i", "ඉ"),
            Map.entry("j", "ජ"),
            Map.entry("k", "ක"),
            Map.entry("l", "ල"),
            Map.entry("m", "ම"),
            Map.entry("n", "න"),
            Map.entry("o", "ඔ"),
            Map.entry("p", "ප"),
            Map.entry("q", "ක"),
            Map.entry("r", "ර"),
            Map.entry("s", "ස"),
            Map.entry("t", "ට"),
            Map.entry("u", "උ"),
            Map.entry("v", "ව"),
            Map.entry("w", "ව"),
            Map.entry("x", "ක්ස්"),
            Map.entry("y", "ය"),
            Map.entry("z", "ස"));

    private static final Map<String, String> LATIN_TO_TA_DOUBLE = Map.ofEntries(
            Map.entry("ch", "ச"),
            Map.entry("sh", "ஷ"),
            Map.entry("th", "த"),
            Map.entry("dh", "த"),
            Map.entry("ph", "ப"),
            Map.entry("kh", "க"),
            Map.entry("gh", "க"),
            Map.entry("aa", "ஆ"),
            Map.entry("ee", "ஈ"),
            Map.entry("oo", "ஊ"),
            Map.entry("ai", "ஐ"),
            Map.entry("au", "ஔ"));

    private static final Map<String, String> LATIN_TO_TA_SINGLE = Map.ofEntries(
            Map.entry("a", "அ"),
            Map.entry("b", "ப"),
            Map.entry("c", "க"),
            Map.entry("d", "ட"),
            Map.entry("e", "எ"),
            Map.entry("f", "ப"),
            Map.entry("g", "க"),
            Map.entry("h", "ஹ"),
            Map.entry("i", "இ"),
            Map.entry("j", "ஜ"),
            Map.entry("k", "க"),
            Map.entry("l", "ல"),
            Map.entry("m", "ம"),
            Map.entry("n", "ந"),
            Map.entry("o", "ஒ"),
            Map.entry("p", "ப"),
            Map.entry("q", "க"),
            Map.entry("r", "ர"),
            Map.entry("s", "ஸ"),
            Map.entry("t", "ட"),
            Map.entry("u", "உ"),
            Map.entry("v", "வ"),
            Map.entry("w", "வ"),
            Map.entry("x", "க்ஸ்"),
            Map.entry("y", "ய"),
            Map.entry("z", "ஸ"));
}
