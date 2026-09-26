package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 22A section 9: "GtinParser check digits". */
class GtinParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    @Test
    void acceptsTheFourGtinLengthsWithTheRightCheckDigit() {
        assertThat(GtinParser.isValidGtin("96385074")).as("GTIN-8").isTrue();
        assertThat(GtinParser.isValidGtin("036000291452")).as("GTIN-12 / UPC-A").isTrue();
        assertThat(GtinParser.isValidGtin("4791234567891"))
                .as("GTIN-13 / EAN-13")
                .isTrue();
        assertThat(GtinParser.isValidGtin("04791234567891")).as("GTIN-14").isTrue();
    }

    @Test
    void refusesAWrongCheckDigitAWrongLengthAndNonDigits() {
        assertThat(GtinParser.isValidGtin("4791234567890")).isFalse();
        assertThat(GtinParser.isValidGtin("96385075")).isFalse();
        assertThat(GtinParser.isValidGtin("479123456789"))
                .as("twelve digits of a thirteen-digit code")
                .isFalse();
        assertThat(GtinParser.isValidGtin("47912345678AB")).isFalse();
        assertThat(GtinParser.isValidGtin("")).isFalse();
        assertThat(GtinParser.isValidGtin(null)).isFalse();
    }

    @Test
    void computesTheCheckDigitFromTheRightWithWeightsThreeAndOne() {
        assertThat(GtinParser.checkDigit("479123456789")).isEqualTo(1);
        assertThat(GtinParser.checkDigit("9638507")).isEqualTo(4);
        assertThat(GtinParser.checkDigit("03600029145")).isEqualTo(2);
    }

    @Test
    void splitsAnElementStringIntoGtinExpiryAndLot() {
        Optional<GtinParser.Gs1Elements> elements =
                GtinParser.parseElementString("01047912345678911727033110B2411A", TODAY);

        assertThat(elements).isPresent();
        assertThat(elements.get().gtin()).isEqualTo("04791234567891");
        assertThat(elements.get().expiry()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(elements.get().lot()).isEqualTo("B2411A");
    }

    @Test
    void theLotEndsAtTheGroupSeparatorSoAnExpiryMayFollowIt() {
        Optional<GtinParser.Gs1Elements> elements = GtinParser.parseElementString(
                "010479123456789110B2411A" + GtinParser.GROUP_SEPARATOR + "17270331", TODAY);

        assertThat(elements).isPresent();
        assertThat(elements.get().lot()).isEqualTo("B2411A");
        assertThat(elements.get().expiry()).isEqualTo(LocalDate.of(2027, 3, 31));
    }

    @Test
    void aStringThatIsNotAnElementStringOrCarriesABadGtinIsNotSplit() {
        assertThat(GtinParser.parseElementString("4791234567891", TODAY))
                .as("a plain EAN-13")
                .isEmpty();
        assertThat(GtinParser.parseElementString("0104791234567890", TODAY))
                .as("wrong check digit")
                .isEmpty();
        assertThat(GtinParser.parseElementString("01047912345678", TODAY))
                .as("too short for AI 01")
                .isEmpty();
        assertThat(GtinParser.parseElementString("010479123456789199ABC", TODAY))
                .as("unknown AI 99")
                .isEmpty();
        assertThat(GtinParser.parseElementString(null, TODAY)).isEmpty();
    }

    @Test
    void anExpiryDayOfZeroMeansTheEndOfTheMonth() {
        assertThat(GtinParser.expiryDate("270200", TODAY)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(GtinParser.expiryDate("271301", TODAY)).as("month 13").isNull();
        assertThat(GtinParser.expiryDate("27033", TODAY)).as("five digits").isNull();
    }

    @Test
    void leadingZerosDoNotMakeAnotherItem() {
        assertThat(GtinParser.withoutLeadingZeros("04791234567891")).isEqualTo("4791234567891");
        assertThat(GtinParser.withoutLeadingZeros("4791234567891")).isEqualTo("4791234567891");
        assertThat(GtinParser.withoutLeadingZeros("0")).isEqualTo("0");
    }
}
