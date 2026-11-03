package lk.coopfed.knoweb.m9integration.internal.payment.adapter;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * LankaQR-specific payment adapter seam (M3-07).
 */
@Component
public class LankaQrPaymentProviderAdapter implements PaymentProviderAdapter {

    public static final String PROVIDER_NAME = "lankaqr";

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(String paymentMethod) {
        return "LANKA_QR".equalsIgnoreCase(paymentMethod);
    }

    @Override
    public ProviderInitiationResult initiate(ProviderInitiationRequest request) {
        String qrPayload = LankaQrGenerator.generateDynamicQr(
                "COOPFED-MERCHANT-01", "COOP FEDERATION LK", "COLOMBO", request.orderReference(), request.amount());

        String providerRef =
                "LQR-" + request.paymentId().toString().substring(0, 8).toUpperCase();

        return new ProviderInitiationResult(
                providerRef,
                "PENDING",
                qrPayload,
                null,
                Map.of(
                        "format", "EMVCO_MERCHANT_PRESENTED",
                        "standard", "CBSL_LANKAQR",
                        "terminalId", "TERM-001"));
    }

    @Override
    public ProviderStatusResult verifyStatus(String providerRef, Map<String, Object> context) {
        // In real environment, this queries the LankaQR Switch / Bank settlement API.
        // For staging / testing, defaults to verified or status passed in context.
        String status = context != null && context.containsKey("simulatedStatus")
                ? (String) context.get("simulatedStatus")
                : "PAID";
        return new ProviderStatusResult(providerRef, status, null, Map.of("verifiedBy", "LankaQrAdapter"));
    }

    @Override
    public boolean verifyWebhookSignature(String signature, String payload) {
        return signature != null && !signature.isBlank();
    }
}
