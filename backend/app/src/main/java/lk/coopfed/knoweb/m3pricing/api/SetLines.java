package lk.coopfed.knoweb.m3pricing.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * SetLines (23A section 7; doc 23 section 5.1, bulk): the complete set of lines of a DRAFT list.
 * The lines replace those the draft had; a line's tier is the quantity from which its price
 * applies (0 is the base price, doc 23 section 3.2).
 */
public record SetLines(UUID priceListId, List<Line> lines) {

    public record Line(UUID skuId, String uomCode, BigDecimal tierFromQty, BigDecimal price) {}
}
