package lk.coopfed.knoweb.m7customers.internal.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Phone numbers to E.164, the NIC's canonical form, legacy forms and last four (27A sections 4 and
 * 6; wave 2, M7CR-02), and the value guard on officer free text (M7CR-10). Every number here is
 * plainly made up.
 */
class IdentityNumbersTest {

    @Test
    void sriLankanNumbersAsTypedAtACounterBecomeE164() {
        assertThat(PhoneNumbers.normalise("0700000101")).contains("+94700000101");
        assertThat(PhoneNumbers.normalise("070 000 0101")).contains("+94700000101");
        assertThat(PhoneNumbers.normalise("070-000-0101")).contains("+94700000101");
        assertThat(PhoneNumbers.normalise("94700000101")).contains("+94700000101");
        assertThat(PhoneNumbers.normalise("+94700000101")).contains("+94700000101");
        assertThat(PhoneNumbers.normalise("12345")).isEmpty();
        assertThat(PhoneNumbers.normalise("+44700000101")).isEmpty();
        assertThat(PhoneNumbers.normalise(null)).isEmpty();
    }

    @Test
    void theOldAndTheNewFormOfOneCardCanonicaliseAlikeAndVAndXAlike() {
        // Old form 900000001V: YY=90, DDD=000, SSS=000, C=1 -> 19 90000 0 0001.
        assertThat(NicNumbers.canonical("900000001V")).contains("199000000001");
        assertThat(NicNumbers.canonical("900000001x")).contains("199000000001");
        assertThat(NicNumbers.canonical("9000 0000 1V")).contains("199000000001");
        assertThat(NicNumbers.canonical("199000000001")).contains("199000000001");
        assertThat(NicNumbers.canonical("1990 0000 0001")).contains("199000000001");
        assertThat(NicNumbers.canonical("12345")).isEmpty();
        assertThat(NicNumbers.canonical(null)).isEmpty();
        assertThat(NicNumbers.normalise("100000001v")).contains("100000001V");
    }

    @Test
    void theLastFourComeFromTheCanonicalFormWhicheverCardWasTyped() {
        assertThat(NicNumbers.last4(NicNumbers.canonical("900000001V").orElseThrow()))
                .isEqualTo("0001");
        assertThat(NicNumbers.last4(NicNumbers.canonical("199000000001").orElseThrow()))
                .isEqualTo("0001");
    }

    @Test
    void theLegacyFormsOfACanonicalNumberAreTheThreeARowMayHold() {
        assertThat(NicNumbers.legacyForms("199000000001")).containsExactly("199000000001", "900000001V", "900000001X");
        // A number with no old form (born after 1999, or a seventh digit that is not 0): itself only.
        assertThat(NicNumbers.legacyForms("200512345678")).containsExactly("200512345678");
        assertThat(NicNumbers.legacyForms("199000010001")).containsExactly("199000010001");
    }

    @Test
    void theLegacySchemeIsAPlainSha256AndNeverTheNumber() {
        String hash = NicNumbers.legacySha256("199000000001");
        assertThat(hash)
                .hasSize(64)
                .isEqualTo(NicNumbers.legacySha256("199000000001"))
                .doesNotContain("199000000001")
                .isNotEqualTo(NicNumbers.legacySha256("900000001V"));
    }

    @Test
    void officerFreeTextMayNotCarryAPhoneNumberOrANic() {
        assertThat(PersonalDataText.require("Pays on time", "reason")).isEqualTo("Pays on time");
        assertThat(PersonalDataText.require("  Bank slip 4471  ", "reference")).isEqualTo("Bank slip 4471");
        assertThat(PersonalDataText.require(null, "notes")).isNull();
        for (String text : new String[] {
            "new number 0771234567",
            "call 077-123 4567",
            "reach on +94 77 123 4567",
            "card 900000001V",
            "card 900000001v",
            "NIC 199000000001"
        }) {
            assertThatThrownBy(() -> PersonalDataText.require(text, "reason"))
                    .as(text)
                    .hasMessageContaining("m7.field.personal_data");
        }
        // Shorter numbers are not a phone or a NIC: a receipt or a slip number passes.
        assertThat(PersonalDataText.require("RCT-000123456", "reason")).isEqualTo("RCT-000123456");
    }
}
