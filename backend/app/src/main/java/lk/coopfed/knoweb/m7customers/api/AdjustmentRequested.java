package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** An adjustment of an account was asked for; nothing is posted until another person approves it. */
public record AdjustmentRequested(UUID adjustmentId, UUID accountId, UUID ownerEntityId, BigDecimal amount)
        implements DomainEvent {

    public static final String TYPE = "account.adjustment_requested.v1";
}
