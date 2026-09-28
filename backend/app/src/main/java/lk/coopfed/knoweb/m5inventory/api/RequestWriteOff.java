package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * RequestWriteOff (25A section 6.3; doc 25 flow 6.4): a draft write-off of stock at one location,
 * in one category, with its lines. Photographs are added to the draft, then it is submitted.
 */
public record RequestWriteOff(UUID locationId, LossCategory category, String note, List<Line> lines) {

    /** A lot of the location and the quantity lost, in the SKU's base unit. */
    public record Line(UUID batchId, LotCondition condition, BigDecimal qty) {}
}
