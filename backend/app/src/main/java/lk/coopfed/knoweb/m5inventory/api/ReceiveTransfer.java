package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * ReceiveTransfer (25A section 6.3; doc 25 flow 6.6): the destination receives what the source
 * sent. Omitting {@code lines} preserves the original full-receipt command; supplied quantities
 * name every immutable transfer line. Short quantities remain refused until the adjustment
 * workflow is approved.
 */
public record ReceiveTransfer(UUID transferId, List<Line> lines) {

    /** Backward-compatible full receipt used by existing callers. */
    public ReceiveTransfer(UUID transferId) {
        this(transferId, null);
    }

    public record Line(UUID transferLineId, BigDecimal receivedQty) {}
}
