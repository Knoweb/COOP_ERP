package lk.coopfed.knoweb.m9integration.internal.payment.adapter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Standard LankaQR (EMVCo Merchant-Presented Mode) QR Code Payload Generator (M3-07).
 * Conforms to the Central Bank of Sri Lanka (CBSL) LankaQR specification.
 */
public final class LankaQrGenerator {

    private LankaQrGenerator() {}

    /**
     * Generates a dynamic LankaQR EMVCo compliant string.
     *
     * @param merchantId     The merchant account identifier
     * @param merchantName   Merchant business name (ASCII, max 25 chars)
     * @param merchantCity   Merchant city name (ASCII, max 15 chars)
     * @param orderReference Order or bill reference
     * @param amount         Payment amount in LKR
     * @return Full EMVCo TLV string with valid CRC-16 checksum
     */
    public static String generateDynamicQr(
            String merchantId, String merchantName, String merchantCity, String orderReference, BigDecimal amount) {

        StringBuilder payload = new StringBuilder();

        // 00: Payload Format Indicator ("01")
        payload.append(tlv("00", "01"));

        // 01: Point of Initiation Method ("12" = Dynamic QR, amount included)
        payload.append(tlv("01", "12"));

        // 26: Merchant Account Information - LankaQR (CBSL GUID + Merchant ID)
        String merchantGuid = "lk.cbsl.lankaqr";
        String merchantAccountData = tlv("00", merchantGuid) + tlv("01", merchantId != null ? merchantId : "COOPFED01");
        payload.append(tlv("26", merchantAccountData));

        // 52: Merchant Category Code ("5411" = Grocery stores, Supermarkets)
        payload.append(tlv("52", "5411"));

        // 53: Transaction Currency ("144" = LKR numeric ISO 4217 code)
        payload.append(tlv("53", "144"));

        // 54: Transaction Amount
        String formattedAmount = String.format(java.util.Locale.US, "%.2f", amount);
        payload.append(tlv("54", formattedAmount));

        // 58: Country Code ("LK")
        payload.append(tlv("58", "LK"));

        // 59: Merchant Name
        String sanitizedName = sanitize(merchantName != null ? merchantName : "COOPFED", 25);
        payload.append(tlv("59", sanitizedName));

        // 60: Merchant City
        String sanitizedCity = sanitize(merchantCity != null ? merchantCity : "COLOMBO", 15);
        payload.append(tlv("60", sanitizedCity));

        // 62: Additional Data Field Template (Bill/Order Number subtag 01)
        if (orderReference != null && !orderReference.isBlank()) {
            String sanitizedRef = sanitize(orderReference, 25);
            String additionalData = tlv("01", sanitizedRef);
            payload.append(tlv("62", additionalData));
        }

        // 63: CRC placeholder (Tag "63", Length "04")
        payload.append("6304");

        // Compute EMVCo CRC-16 over all bytes up to "6304"
        int crc = computeCrc16(payload.toString().getBytes(StandardCharsets.US_ASCII));
        String crcHex = String.format("%04X", crc);

        return payload.toString() + crcHex;
    }

    private static String tlv(String tag, String value) {
        int length = value.length();
        return tag + String.format("%02d", length) + value;
    }

    private static String sanitize(String text, int maxLength) {
        String clean = text.replaceAll("[^a-zA-Z0-9 _-]", "").trim();
        if (clean.length() > maxLength) {
            clean = clean.substring(0, maxLength);
        }
        return clean.isEmpty() ? "COOP" : clean;
    }

    /**
     * EMVCo standard CRC-16 (CCITT, polynomial 0x1021, init 0xFFFF, non-reflected).
     */
    public static int computeCrc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            crc ^= ((b & 0xFF) << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
