package lk.coopfed.knoweb.m9integration.internal.einvoice;

import org.springframework.stereotype.Component;

/**
 * Phase 1 e-invoicing adapter (ACC-10).
 *
 * No network calls are made. Every submission stays PENDING
 * until a real provider adapter is introduced in a later phase.
 */
@Component
public final class StubEInvoiceAdapter implements EInvoiceAdapter {

    @Override
    public SubmissionResult submit(InvoiceSnapshot invoice) {
        return new SubmissionResult("PENDING", null, null);
    }
}
