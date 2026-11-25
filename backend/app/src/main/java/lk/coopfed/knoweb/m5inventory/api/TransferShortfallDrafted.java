package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A destination recorded the quantities missing from a transfer as an unposted draft. */
public record TransferShortfallDrafted(
        UUID shortfallId,
        UUID transferId,
        UUID ownerEntityId,
        UUID fromLocationId,
        UUID toLocationId,
        int lines,
        BigDecimal shortfallQty,
        BigDecimal shortfallValue)
        implements DomainEvent {

    public static final String TYPE = "transfer.shortfall.drafted.v1";
}
