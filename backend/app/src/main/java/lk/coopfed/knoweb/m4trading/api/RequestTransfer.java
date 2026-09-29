package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * RequestLateralTransfer (24A section 6; doc 24 section 4.7): a shop asks for stock from another
 * location of its own society, usually the society's stores. The society approves or rejects; on
 * approval M5 issues the transfer.
 *
 * @param toLocationId the shop that asks (the caller's own location for a shop session)
 */
public record RequestTransfer(UUID fromLocationId, UUID toLocationId, String reason, List<Line> lines) {

    public RequestTransfer {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** An item and a quantity in its base unit. */
    public record Line(UUID skuId, BigDecimal qty) {}
}
