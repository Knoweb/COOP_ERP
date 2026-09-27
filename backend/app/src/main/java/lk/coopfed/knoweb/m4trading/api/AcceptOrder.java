package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * AcceptOrder (24A section 6; section 6.2): the seller accepts a submitted order with a committed
 * ETA. Each line is allocated what is open of it, up to what the seller has available; an override
 * replaces a line's allocation and carries a reason.
 */
public record AcceptOrder(UUID orderId, LocalDate committedEta, List<LineOverride> overrides) {

    public record LineOverride(UUID lineId, BigDecimal allocatedQty, String reason) {}
}
