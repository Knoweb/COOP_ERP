package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** The limit, hard block or offline cap of an account changed. Ids and amounts only. */
public record AccountLimitsAmended(
        UUID accountId,
        UUID customerId,
        UUID ownerEntityId,
        BigDecimal creditLimit,
        boolean hardBlock,
        BigDecimal offlineCap)
        implements DomainEvent {

    public static final String TYPE = "account.limit_amended.v1";
}
