package lk.coopfed.knoweb.m2catalogue.internal.steward;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Duplicate scoring engine for catalogue SKUs (CAT-01, 22A section 6.1).
 *
 * <p>Calculates similarity between candidate and existing items:
 * <ul>
 *   <li>0.55 * Jaro-Winkler(key(candidate.nameEn), key(existing.nameEn))</li>
 *   <li>0.20 * [brand equal]</li>
 *   <li>0.15 * [pack equal within 2%]</li>
 *   <li>0.10 * [same base uom]</li>
 *   <li>+0.30 if any barcode matches (capped at 1.0)</li>
 * </ul>
 *
 * <p>Pairs with score &ge; duplicate threshold (default 0.85) are flagged as suspects.
 */
@Component
public class DuplicateScorer {

    public static final double DEFAULT_THRESHOLD = 0.85;

    public static final String REASON_NAME_SIMILARITY = "NAME_SIMILARITY";
    public static final String REASON_BRAND_MATCH = "BRAND_MATCH";
    public static final String REASON_PACK_MATCH = "PACK_MATCH";
    public static final String REASON_SAME_UOM = "SAME_UOM";
    public static final String REASON_BARCODE_MATCH = "BARCODE_MATCH";

    private static final Pattern PACK_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(kg|g|l|ml|pkt|pcs|pkts|packs)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> KNOWN_BRANDS = Set.of(
            "anchor",
            "maliban",
            "munchee",
            "cargills",
            "keells",
            "kotmale",
            "pelwatte",
            "nestle",
            "sunlight",
            "signal",
            "dilmah",
            "knorr",
            "maggi",
            "harischandra",
            "wijaya",
            "md",
            "raththi",
            "highland",
            "lipton",
            "coca-cola",
            "pepsi",
            "elephant",
            "rajarata",
            "araliya",
            "cic",
            "dsm",
            "flora",
            "glow");

    public record ItemProfile(
            UUID skuId,
            String skuCode,
            String nameEn,
            String nameSi,
            String nameTa,
            String baseUom,
            String brand,
            List<String> barcodes) {}

    public record ScoreResult(BigDecimal score, boolean isSuspect, List<String> reasons) {}

    public record SuspectMatch(ItemProfile candidate, ItemProfile matched, BigDecimal score, List<String> reasons) {}

    private final Transliterator transliterator;

    public DuplicateScorer(Transliterator transliterator) {
        this.transliterator = transliterator;
    }

    /**
     * Scores a candidate against an existing item profile using DEFAULT_THRESHOLD.
     */
    public ScoreResult score(ItemProfile candidate, ItemProfile existing) {
        return score(candidate, existing, DEFAULT_THRESHOLD);
    }

    /**
     * Scores a candidate against an existing item profile.
     */
    public ScoreResult score(ItemProfile candidate, ItemProfile existing, double threshold) {
        if (candidate == null || existing == null) {
            return new ScoreResult(BigDecimal.ZERO, false, List.of());
        }

        List<String> reasons = new ArrayList<>();
        double totalScore = 0.0;

        // 1. Normalized Latin Name Key & Jaro-Winkler similarity (weight: 0.55)
        String candKey = toKey(candidate.nameEn(), candidate.nameSi(), candidate.nameTa());
        String existKey = toKey(existing.nameEn(), existing.nameSi(), existing.nameTa());

        double jw = jaroWinkler(candKey, existKey);
        if (jw > 0.0) {
            totalScore += 0.55 * jw;
            if (jw >= 0.70) {
                reasons.add(REASON_NAME_SIMILARITY);
            }
        }

        // 2. Brand Match (weight: 0.20)
        String candBrand = extractBrand(candidate.brand(), candKey);
        String existBrand = extractBrand(existing.brand(), existKey);
        if (candBrand != null && candBrand.equalsIgnoreCase(existBrand)) {
            totalScore += 0.20;
            reasons.add(REASON_BRAND_MATCH);
        }

        // 3. Pack Size Match within 2% (weight: 0.15)
        Double candPack = extractPackBase(candKey);
        Double existPack = extractPackBase(existKey);
        if (candPack != null && existPack != null) {
            double diff = Math.abs(candPack - existPack);
            double max = Math.max(candPack, existPack);
            if (max > 0.0 && (diff / max) <= 0.02) {
                totalScore += 0.15;
                reasons.add(REASON_PACK_MATCH);
            }
        }

        // 4. Same Base UOM (weight: 0.10)
        if (candidate.baseUom() != null
                && existing.baseUom() != null
                && candidate.baseUom().equalsIgnoreCase(existing.baseUom())) {
            totalScore += 0.10;
            reasons.add(REASON_SAME_UOM);
        }

        // 5. Barcode Match Boost (+0.30, capped at 1.0)
        if (hasSharedBarcode(candidate.barcodes(), existing.barcodes())) {
            totalScore += 0.30;
            reasons.add(REASON_BARCODE_MATCH);
        }

        double finalScore = Math.min(1.0, totalScore);
        BigDecimal rounded = BigDecimal.valueOf(finalScore).setScale(3, RoundingMode.HALF_UP);
        boolean suspect = finalScore >= threshold;

        return new ScoreResult(rounded, suspect, reasons);
    }

    /**
     * Finds duplicates within a batch of incoming candidate items (e.g. from an import sheet).
     * Uses blocking by barcode, brand, and prefix for high throughput on large batches.
     */
    public List<SuspectMatch> scoreWithinBatch(List<ItemProfile> items, double threshold) {
        if (items == null || items.size() < 2) {
            return List.of();
        }

        List<SuspectMatch> suspects = new ArrayList<>();
        Set<String> evaluatedPairs = new HashSet<>();

        // 1. Barcode blocking
        Map<String, List<ItemProfile>> barcodeIndex = new HashMap<>();
        for (ItemProfile item : items) {
            if (item.barcodes() != null) {
                for (String b : item.barcodes()) {
                    if (b != null && !b.isBlank()) {
                        barcodeIndex
                                .computeIfAbsent(b.strip(), k -> new ArrayList<>())
                                .add(item);
                    }
                }
            }
        }

        for (List<ItemProfile> group : barcodeIndex.values()) {
            if (group.size() > 1) {
                for (int i = 0; i < group.size(); i++) {
                    for (int j = i + 1; j < group.size(); j++) {
                        checkPair(group.get(i), group.get(j), threshold, evaluatedPairs, suspects);
                    }
                }
            }
        }

        // 2. Prefix & Brand blocking
        Map<String, List<ItemProfile>> blockIndex = new HashMap<>();
        for (ItemProfile item : items) {
            String key = toKey(item.nameEn(), item.nameSi(), item.nameTa());
            String prefix = key.length() >= 4 ? key.substring(0, 4) : key;
            blockIndex.computeIfAbsent(prefix, k -> new ArrayList<>()).add(item);

            String brand = extractBrand(item.brand(), key);
            if (brand != null) {
                blockIndex
                        .computeIfAbsent("brand:" + brand, k -> new ArrayList<>())
                        .add(item);
            }
        }

        for (List<ItemProfile> block : blockIndex.values()) {
            if (block.size() > 1) {
                for (int i = 0; i < block.size(); i++) {
                    for (int j = i + 1; j < block.size(); j++) {
                        checkPair(block.get(i), block.get(j), threshold, evaluatedPairs, suspects);
                    }
                }
            }
        }

        return suspects;
    }

    /**
     * Finds duplicate suspects for candidates against existing database catalogue items.
     */
    public List<SuspectMatch> scoreAgainstExisting(
            List<ItemProfile> candidates, List<ItemProfile> existing, double threshold) {
        if (candidates == null || candidates.isEmpty() || existing == null || existing.isEmpty()) {
            return List.of();
        }

        List<SuspectMatch> suspects = new ArrayList<>();
        Set<String> evaluatedPairs = new HashSet<>();

        // Index existing by barcode and prefix
        Map<String, List<ItemProfile>> existBarcode = new HashMap<>();
        Map<String, List<ItemProfile>> existPrefix = new HashMap<>();

        for (ItemProfile ex : existing) {
            if (ex.barcodes() != null) {
                for (String b : ex.barcodes()) {
                    if (b != null && !b.isBlank()) {
                        existBarcode
                                .computeIfAbsent(b.strip(), k -> new ArrayList<>())
                                .add(ex);
                    }
                }
            }
            String key = toKey(ex.nameEn(), ex.nameSi(), ex.nameTa());
            String pfx = key.length() >= 4 ? key.substring(0, 4) : key;
            existPrefix.computeIfAbsent(pfx, k -> new ArrayList<>()).add(ex);

            String brand = extractBrand(ex.brand(), key);
            if (brand != null) {
                existPrefix
                        .computeIfAbsent("brand:" + brand, k -> new ArrayList<>())
                        .add(ex);
            }
        }

        for (ItemProfile cand : candidates) {
            Set<ItemProfile> pool = new HashSet<>();

            // Check barcodes
            if (cand.barcodes() != null) {
                for (String b : cand.barcodes()) {
                    if (b != null && existBarcode.containsKey(b.strip())) {
                        pool.addAll(existBarcode.get(b.strip()));
                    }
                }
            }

            // Check prefix & brand
            String candKey = toKey(cand.nameEn(), cand.nameSi(), cand.nameTa());
            String pfx = candKey.length() >= 4 ? candKey.substring(0, 4) : candKey;
            if (existPrefix.containsKey(pfx)) {
                pool.addAll(existPrefix.get(pfx));
            }

            String brand = extractBrand(cand.brand(), candKey);
            if (brand != null && existPrefix.containsKey("brand:" + brand)) {
                pool.addAll(existPrefix.get("brand:" + brand));
            }

            for (ItemProfile ex : pool) {
                checkPair(cand, ex, threshold, evaluatedPairs, suspects);
            }
        }

        return suspects;
    }

    private void checkPair(
            ItemProfile a, ItemProfile b, double threshold, Set<String> evaluatedPairs, List<SuspectMatch> suspects) {
        String pairKey =
                a.hashCode() < b.hashCode() ? a.hashCode() + ":" + b.hashCode() : b.hashCode() + ":" + a.hashCode();
        if (!evaluatedPairs.add(pairKey)) {
            return;
        }

        ScoreResult result = score(a, b, threshold);
        if (result.isSuspect()) {
            suspects.add(new SuspectMatch(a, b, result.score(), result.reasons()));
        }
    }

    private boolean hasSharedBarcode(List<String> listA, List<String> listB) {
        if (listA == null || listB == null || listA.isEmpty() || listB.isEmpty()) {
            return false;
        }
        Set<String> setA = new HashSet<>();
        for (String s : listA) {
            if (s != null && !s.isBlank()) setA.add(s.strip());
        }
        for (String s : listB) {
            if (s != null && setA.contains(s.strip())) {
                return true;
            }
        }
        return false;
    }

    public String toKey(String en, String si, String ta) {
        StringBuilder sb = new StringBuilder();
        if (en != null && !en.isBlank()) {
            sb.append(en.strip()).append(" ");
        }
        if (si != null && !si.isBlank()) {
            sb.append(transliterator.toLatin(si)).append(" ");
        }
        if (ta != null && !ta.isBlank()) {
            sb.append(transliterator.toLatin(ta)).append(" ");
        }

        String raw = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return raw.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private String extractBrand(String explicitBrand, String key) {
        if (explicitBrand != null && !explicitBrand.isBlank()) {
            return explicitBrand.strip().toLowerCase(Locale.ROOT);
        }
        String[] tokens = key.split("\\s+");
        if (tokens.length > 0 && KNOWN_BRANDS.contains(tokens[0])) {
            return tokens[0];
        }
        return null;
    }

    private Double extractPackBase(String key) {
        Matcher m = PACK_PATTERN.matcher(key);
        if (m.find()) {
            double val = Double.parseDouble(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            return switch (unit) {
                case "kg", "l" -> val * 1000.0;
                case "g", "ml" -> val;
                default -> val;
            };
        }
        return null;
    }

    /**
     * Standard Jaro-Winkler similarity algorithm between two character sequences.
     */
    public static double jaroWinkler(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        int matchDistance = Math.max(s1.length(), s2.length()) / 2 - 1;

        boolean[] s1Matches = new boolean[s1.length()];
        boolean[] s2Matches = new boolean[s2.length()];

        int matches = 0;
        for (int i = 0; i < s1.length(); i++) {
            int start = Math.max(0, i - matchDistance);
            int end = Math.min(i + matchDistance + 1, s2.length());

            for (int j = start; j < end; j++) {
                if (s2Matches[j]) continue;
                if (s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) return 0.0;

        int k = 0;
        int transpositions = 0;
        for (int i = 0; i < s1.length(); i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        double jaro = ((double) matches / s1.length()
                        + (double) matches / s2.length()
                        + (double) (matches - transpositions / 2) / matches)
                / 3.0;

        // Winkler prefix bonus (up to 4 matching initial chars with p = 0.1)
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(s1.length(), s2.length())); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }

        return jaro + prefix * 0.1 * (1.0 - jaro);
    }
}
