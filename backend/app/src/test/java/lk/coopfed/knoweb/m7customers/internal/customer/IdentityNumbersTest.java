package lk.coopfed.knoweb.m7customers.internal.customer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Phone numbers to E.164 and the NIC's hash and last four (27A sections 4 and 6). */
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
    void theOldAndTheNewNicAreAcceptedAndOnlyTheirHashAndLastFourKept() {
        assertThat(NicNumbers.normalise("100000001v")).contains("100000001V");
        assertThat(NicNumbers.normalise("1900 0000 0001")).contains("190000000001");
        assertThat(NicNumbers.normalise("12345")).isEmpty();
        String hash = NicNumbers.hash("190000000001");
        assertThat(hash).hasSize(64).isEqualTo(NicNumbers.hash("190000000001")).doesNotContain("190000000001");
        assertThat(NicNumbers.last4("100000001V")).isEqualTo("001V");
    }
}
