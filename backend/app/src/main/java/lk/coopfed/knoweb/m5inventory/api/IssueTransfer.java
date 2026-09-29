package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * IssueTransfer (25A section 6.3; doc 25 flow 6.6): stock of one entity leaves one of its
 * locations for another (a warehouse to a shop). Issue and dispatch are one step at demo scope:
 * the stock leaves the source as TRANSFER_OUT and is in transit until the destination receives.
 *
 * @param lines             at least one; each a GOOD batch at the source and a quantity it holds
 * @param transferRequestId the M4 transfer request the transfer fulfils (M4-10), or null for a
 *                          transfer issued by hand; a request is fulfilled by one transfer only
 */
public record IssueTransfer(UUID fromLocationId, UUID toLocationId, List<Line> lines, UUID transferRequestId) {

    /** A transfer issued by hand, for no request. */
    public IssueTransfer(UUID fromLocationId, UUID toLocationId, List<Line> lines) {
        this(fromLocationId, toLocationId, lines, null);
    }

    /** A batch and a quantity in the SKU's base unit. */
    public record Line(UUID batchId, BigDecimal qty) {}
}
