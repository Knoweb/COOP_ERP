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
 * @param shortfalls        the request's items that could not be sent in full (wave 2, M5-03): the
 *                          request is filled once and the rest is flagged, never sent later; empty
 *                          for a transfer issued by hand
 */
public record IssueTransfer(
        UUID fromLocationId, UUID toLocationId, List<Line> lines, UUID transferRequestId, List<Shortfall> shortfalls) {

    public IssueTransfer {
        shortfalls = shortfalls == null ? List.of() : List.copyOf(shortfalls);
    }

    /** A transfer for a request that is sent in full. */
    public IssueTransfer(UUID fromLocationId, UUID toLocationId, List<Line> lines, UUID transferRequestId) {
        this(fromLocationId, toLocationId, lines, transferRequestId, List.of());
    }

    /** A transfer issued by hand, for no request. */
    public IssueTransfer(UUID fromLocationId, UUID toLocationId, List<Line> lines) {
        this(fromLocationId, toLocationId, lines, null, List.of());
    }

    /** A batch and a quantity in the SKU's base unit. */
    public record Line(UUID batchId, BigDecimal qty) {}

    /** An item the request wanted more of than the source could send: what was wanted, what went. */
    public record Shortfall(UUID skuId, BigDecimal wanted, BigDecimal sent) {}
}
