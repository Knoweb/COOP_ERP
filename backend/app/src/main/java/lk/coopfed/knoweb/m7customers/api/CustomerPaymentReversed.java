package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A repayment was reversed by a REVERSES-linked CPR. Ids and amounts only. */
public record CustomerPaymentReversed(
        UUID documentId,
        UUID reversalDocumentId,
        String reversalDocNumber,
        UUID accountId,
        UUID customerId,
        UUID ownerEntityId,
        BigDecimal amount,
        BigDecimal balance)
        implements DomainEvent {

    public static final String TYPE = "customer_payment.reversed.v1";
}
