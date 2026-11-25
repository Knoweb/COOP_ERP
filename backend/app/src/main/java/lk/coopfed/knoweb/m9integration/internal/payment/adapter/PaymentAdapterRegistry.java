package lk.coopfed.knoweb.m9integration.internal.payment.adapter;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.springframework.stereotype.Component;

/**
 * Registry resolving payment provider adapters dynamically based on provider name and payment method (M3-07).
 */
@Component
public class PaymentAdapterRegistry {

    private final List<PaymentProviderAdapter> adapters;

    public PaymentAdapterRegistry(List<PaymentProviderAdapter> adapters) {
        this.adapters = adapters != null ? adapters : List.of();
    }

    /**
     * Resolves an adapter by requested provider name or falls back to an adapter supporting the payment method.
     */
    public PaymentProviderAdapter resolve(String providerName, String paymentMethod) {
        if (providerName != null && !providerName.isBlank()) {
            for (PaymentProviderAdapter adapter : adapters) {
                if (adapter.providerName().equalsIgnoreCase(providerName.trim())) {
                    return adapter;
                }
            }
        }

        // Fallback by supported payment method
        for (PaymentProviderAdapter adapter : adapters) {
            if (adapter.supports(paymentMethod)) {
                return adapter;
            }
        }

        throw new ProblemException("m9.payment.provider_not_found");
    }
}
