package lk.coopfed.knoweb.m9integration.internal.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import lk.coopfed.knoweb.m9integration.internal.payment.adapter.LankaQrGenerator;
import org.junit.jupiter.api.Test;

class LankaQrGeneratorTest {

    @Test
    void generatesValidEmvCoDynamicLankaQrString() {
        String qr = LankaQrGenerator.generateDynamicQr(
                "MERCHANT123", "POLGOLLA MPCS", "KANDY", "ORD-98765", new BigDecimal("2500.50"));

        assertThat(qr).isNotNull();
        // Tag 00: Payload format indicator
        assertThat(qr).startsWith("000201");
        // Tag 01: Point of Initiation (12 for dynamic)
        assertThat(qr).contains("010212");
        // Tag 26: Merchant Account Information with CBSL GUID
        assertThat(qr).contains("lk.cbsl.lankaqr");
        assertThat(qr).contains("MERCHANT123");
        // Tag 52: MCC 5411 (Grocery/Retail)
        assertThat(qr).contains("52045411");
        // Tag 53: Currency 144 (LKR)
        assertThat(qr).contains("5303144");
        // Tag 54: Amount 2500.50
        assertThat(qr).contains("54072500.50");
        // Tag 58: Country LK
        assertThat(qr).contains("5802LK");
        // Tag 59: Merchant Name
        assertThat(qr).contains("POLGOLLA MPCS");
        // Tag 60: City
        assertThat(qr).contains("KANDY");
        // Tag 62: Order Ref
        assertThat(qr).contains("ORD-98765");
        // Tag 63: CRC indicator with 4 hex chars at the end
        assertThat(qr).contains("6304");

        // Verify CRC checksum calculation matches
        int crcIndex = qr.lastIndexOf("6304");
        String payloadBeforeCrc = qr.substring(0, crcIndex + 4);
        String reportedCrc = qr.substring(crcIndex + 4);

        int calculatedCrc = LankaQrGenerator.computeCrc16(payloadBeforeCrc.getBytes(StandardCharsets.US_ASCII));
        String expectedCrc = String.format("%04X", calculatedCrc);

        assertThat(reportedCrc).isEqualTo(expectedCrc);
    }
}
