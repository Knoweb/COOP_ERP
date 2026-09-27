package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The sale deductions of one receipt a till issued (25A section 6.2, "receipt.issued.v1: for line
 * with qty&gt;0: post SALE (location, line.batchId, GOOD, -qty)"), read from the till's bundle by
 * {@link SaleConsumer}.
 *
 * @param locationId the location the document names; the sale is posted at the device's shop
 */
record ApplySale(UUID documentId, UUID locationId, Instant issuedAt, List<Line> lines) {

    /** A receipt line: the item, the batch the till resolved (may be missing), the quantity sold. */
    record Line(UUID lineId, int lineNo, UUID skuId, UUID batchId, BigDecimal qty) {}
}
