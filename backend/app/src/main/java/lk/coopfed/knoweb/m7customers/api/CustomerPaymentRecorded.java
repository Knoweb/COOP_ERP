package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A customer's repayment was recorded (a CPR). Ids and amounts only (27A: never a name, phone number or NIC in an event). */
public record CustomerPaymentRecorded(
        UUID documentId,
        String docNumber,
        UUID accountId,
        UUID customerId,
        UUID ownerEntityId,
        BigDecimal amount,
        BigDecimal allocated,
        BigDecimal unallocated,
        BigDecimal balance)
        implements DomainEvent {

    public static final String TYPE = "customer_payment.recorded.v1";
}
