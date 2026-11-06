package lk.coopfed.knoweb.m9integration.internal.payment.adapter;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Mock / fallback payment provider adapter for tests and local development (M3-07).
 */
@Component
public class MockPaymentProviderAdapter implements PaymentProviderAdapter {

    public static final String PROVIDER_NAME = "mock";

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(String paymentMethod) {
        return "ONLINE".equalsIgnoreCase(paymentMethod)
                || "COD".equalsIgnoreCase(paymentMethod)
                || "LANKA_QR".equalsIgnoreCase(paymentMethod);
    }

    @Override
    public ProviderInitiationResult initiate(ProviderInitiationRequest request) {
        String method = request.paymentMethod().toUpperCase();
        String idPrefix = request.paymentId().toString().substring(0, 8).toUpperCase();

        if ("COD".equals(method)) {
            String providerRef = "COD-REF-" + idPrefix;
            return new ProviderInitiationResult(
                    providerRef,
                    "COD_PENDING",
                    null,
                    null,
                    Map.of("settlementType", "CASH_ON_DELIVERY", "trackingCode", "TRK-" + idPrefix));
        }

        if ("LANKA_QR".equals(method)) {
            String qrPayload = LankaQrGenerator.generateDynamicQr(
                    "MOCK-MERCHANT-01", "COOP MOCK STORE", "COLOMBO", request.orderReference(), request.amount());
            String providerRef = "MOCK-LQR-" + idPrefix;
            return new ProviderInitiationResult(providerRef, "PENDING", qrPayload, null, Map.of("qrType", "LANKA_QR"));
        }

        // Default ONLINE payment
        String providerRef = "MOCK-ONLINE-" + idPrefix;
        String checkoutUrl = "https://pay.mock.coopfed.lk/checkout?txn=" + providerRef;
        return new ProviderInitiationResult(
                providerRef,
                "PENDING",
                null,
                checkoutUrl,
                Map.of("gateway", "MOCK_PAYMENT_GATEWAY", "checkoutUrl", checkoutUrl));
    }

    @Override
    public ProviderStatusResult verifyStatus(String providerRef, Map<String, Object> context) {
        String status = context != null && context.containsKey("simulatedStatus")
                ? (String) context.get("simulatedStatus")
                : "PAID";
        return new ProviderStatusResult(providerRef, status, null, Map.of("verifiedBy", "MockPaymentProviderAdapter"));
    }

    @Override
    public boolean verifyWebhookSignature(String signature, String payload) {
        return signature != null && !signature.isBlank();
    }
}
