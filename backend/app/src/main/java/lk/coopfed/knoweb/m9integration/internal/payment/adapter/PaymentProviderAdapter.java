package lk.coopfed.knoweb.m9integration.internal.payment.adapter;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Provider-neutral payment adapter SPI (M3-07).
 * Encapsulates online gateways, LankaQR generators, and COD providers.
 */
public interface PaymentProviderAdapter {

    String providerName();

    boolean supports(String paymentMethod);

    ProviderInitiationResult initiate(ProviderInitiationRequest request);

    ProviderStatusResult verifyStatus(String providerRef, Map<String, Object> context);

    boolean verifyWebhookSignature(String signature, String payload);

    record ProviderInitiationRequest(
            UUID paymentId,
            String orderReference,
            String customerRef,
            BigDecimal amount,
            String currency,
            String paymentMethod,
            String description,
            Map<String, Object> metadata) {}

    record ProviderInitiationResult(
            String providerRef,
            String status,
            String qrPayload,
            String redirectUrl,
            Map<String, Object> providerData) {}

    record ProviderStatusResult(
            String providerRef, String status, String failureReason, Map<String, Object> providerData) {}
}
