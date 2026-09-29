package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** An approved adjustment was posted to an account. Ids and amounts only. */
public record AccountAdjusted(
        UUID accountId,
        UUID customerId,
        UUID ownerEntityId,
        UUID adjustmentId,
        UUID postingId,
        BigDecimal amount,
        BigDecimal balance)
        implements DomainEvent {

    public static final String TYPE = "account.adjusted.v1";
}
