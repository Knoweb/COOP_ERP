package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for central WeighedLabelParser (TILL-SELL-16). */
class WeighedLabelParserTest {

    @Test
    void parsesValidWeighedBarcodePrefix20() {
        // 2012345608504: prefix 20, code 123456, 850 grams, check digit 4
        Optional<WeighedLabelParser.ParsedLabel> parsed = WeighedLabelParser.parse("2012345608504");

        assertThat(parsed).isPresent();
        WeighedLabelParser.ParsedLabel label = parsed.get();
        assertThat(label.prefix()).isEqualTo(20);
        assertThat(label.code()).isEqualTo("123456");
        assertThat(label.weightGrams()).isEqualTo(850);
        assertThat(label.batchRef()).isNull();
        assertThat(label.kind()).isEqualTo(WeighedLabelParser.Kind.WEIGHED);
        assertThat(label.rawData()).isEqualTo("2012345608504");
    }

    @Test
    void parsesValidWeighedBarcodePrefix22() {
        // 2212345615001: prefix 22, code 123456, 1500 grams, check digit 1
        Optional<WeighedLabelParser.ParsedLabel> parsed = WeighedLabelParser.parse("2212345615001");

        assertThat(parsed).isPresent();
        WeighedLabelParser.ParsedLabel label = parsed.get();
        assertThat(label.prefix()).isEqualTo(22);
        assertThat(label.code()).isEqualTo("123456");
        assertThat(label.weightGrams()).isEqualTo(1500);
        assertThat(label.batchRef()).isNull();
        assertThat(label.kind()).isEqualTo(WeighedLabelParser.Kind.WEIGHED);
    }

    @Test
    void parsesValidWeighedBarcodeWithLeadingZerosInCode() {
        // 2000123405001: prefix 20, code 001234, 500 grams, check digit 1
        Optional<WeighedLabelParser.ParsedLabel> parsed = WeighedLabelParser.parse("2000123405001");

        assertThat(parsed).isPresent();
        WeighedLabelParser.ParsedLabel label = parsed.get();
        assertThat(label.prefix()).isEqualTo(20);
        assertThat(label.code()).isEqualTo("001234");
        assertThat(label.weightGrams()).isEqualTo(500);
        assertThat(label.batchRef()).isNull();
        assertThat(label.kind()).isEqualTo(WeighedLabelParser.Kind.WEIGHED);
    }

    @Test
    void parsesValidBatchBarcodePrefix25() {
        // 2512345600428: prefix 25, code 123456, batch 0042, check digit 8
        Optional<WeighedLabelParser.ParsedLabel> parsed = WeighedLabelParser.parse("2512345600428");

        assertThat(parsed).isPresent();
        WeighedLabelParser.ParsedLabel label = parsed.get();
        assertThat(label.prefix()).isEqualTo(25);
        assertThat(label.code()).isEqualTo("123456");
        assertThat(label.weightGrams()).isNull();
        assertThat(label.batchRef()).isEqualTo("0042");
        assertThat(label.kind()).isEqualTo(WeighedLabelParser.Kind.BATCH);
        assertThat(label.rawData()).isEqualTo("2512345600428");
    }

    @Test
    void rejectsZeroWeightBarcodeEvenWithValidChecksum() {
        // 2012345600003: valid EAN-13 check digit 3, but weight is 0 grams
        assertThat(GtinParser.isValidGtin("2012345600003")).isTrue();
        assertThat(WeighedLabelParser.parse("2012345600003")).isEmpty();
    }

    @Test
    void parsesBarcodeWithUnknownSkuCorrectlyLeavingSkuResolutionToQuery() {
        // 2099999908501: prefix 20, code 999999, 850 grams, check digit 1
        Optional<WeighedLabelParser.ParsedLabel> parsed = WeighedLabelParser.parse("2099999908501");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().code()).isEqualTo("999999");
        assertThat(parsed.get().weightGrams()).isEqualTo(850);
    }

    @Test
    void rejectsInvalidCheckDigit() {
        // 2012345608500: check digit 0 is wrong (expected 4)
        assertThat(WeighedLabelParser.parse("2012345608500")).isEmpty();
        // 2512345600424: check digit 4 is wrong (expected 8)
        assertThat(WeighedLabelParser.parse("2512345600424")).isEmpty();
    }

    @Test
    void coversAllPrefixBoundariesFrom20Through29() {
        // Weighed prefixes 20..24
        assertThat(WeighedLabelParser.parse("2012345608504")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(20);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.WEIGHED);
        });
        assertThat(WeighedLabelParser.parse("2112345608501")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(21);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.WEIGHED);
        });
        assertThat(WeighedLabelParser.parse("2212345615001")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(22);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.WEIGHED);
        });
        assertThat(WeighedLabelParser.parse("2312345608505")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(23);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.WEIGHED);
        });
        assertThat(WeighedLabelParser.parse("2412345608502")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(24);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.WEIGHED);
        });

        // Batch prefixes 25..29
        assertThat(WeighedLabelParser.parse("2512345600428")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(25);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.BATCH);
            assertThat(l.batchRef()).isEqualTo("0042");
        });
        assertThat(WeighedLabelParser.parse("2612345600425")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(26);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.BATCH);
            assertThat(l.batchRef()).isEqualTo("0042");
        });
        assertThat(WeighedLabelParser.parse("2712345600422")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(27);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.BATCH);
            assertThat(l.batchRef()).isEqualTo("0042");
        });
        assertThat(WeighedLabelParser.parse("2812345600429")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(28);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.BATCH);
            assertThat(l.batchRef()).isEqualTo("0042");
        });
        assertThat(WeighedLabelParser.parse("2912345600426")).isPresent().get().satisfies(l -> {
            assertThat(l.prefix()).isEqualTo(29);
            assertThat(l.kind()).isEqualTo(WeighedLabelParser.Kind.BATCH);
            assertThat(l.batchRef()).isEqualTo("0042");
        });
    }

    @Test
    void rejectsPrefixesOutside20Through29() {
        assertThat(WeighedLabelParser.parse("1912345608504")).isEmpty();
        assertThat(WeighedLabelParser.parse("3012345608504")).isEmpty();
        assertThat(WeighedLabelParser.parse("0012345608504")).isEmpty();
        assertThat(WeighedLabelParser.parse("4791234567891")).isEmpty();
    }

    @Test
    void rejectsInvalidLengthsAndCharacters() {
        assertThat(WeighedLabelParser.parse(null)).isEmpty();
        assertThat(WeighedLabelParser.parse("")).isEmpty();
        assertThat(WeighedLabelParser.parse("20123456085")).as("11 digits").isEmpty();
        assertThat(WeighedLabelParser.parse("201234560850")).as("12 digits").isEmpty();
        assertThat(WeighedLabelParser.parse("20123456085045")).as("14 digits").isEmpty();
        assertThat(WeighedLabelParser.parse("201234560850A")).as("non-digit").isEmpty();
    }

    @Test
    void testsPotentialInStoreLabelDetection() {
        assertThat(WeighedLabelParser.isPotentialInStoreLabel("2012345608504")).isTrue();
        assertThat(WeighedLabelParser.isPotentialInStoreLabel("2012345608500"))
                .as("bad check digit is still potential")
                .isTrue();
        assertThat(WeighedLabelParser.isPotentialInStoreLabel("2012345600003"))
                .as("zero weight is still potential")
                .isTrue();
        assertThat(WeighedLabelParser.isPotentialInStoreLabel("2998765410012")).isTrue();
        assertThat(WeighedLabelParser.isPotentialInStoreLabel("1912345608504")).isFalse();
        assertThat(WeighedLabelParser.isPotentialInStoreLabel("4791234567891")).isFalse();
        assertThat(WeighedLabelParser.isPotentialInStoreLabel("2012345")).isFalse();
        assertThat(WeighedLabelParser.isPotentialInStoreLabel(null)).isFalse();
    }
}
