package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.util.UUID;

/** One requested shelf label omitted because its list price exceeds the control price in force. */
public record LabelRefusal(
        UUID skuId,
        String skuCode,
        String reason,
        BigDecimal listPrice,
        BigDecimal ceilingPrice,
        String ceilingReference) {

    public static final String ABOVE_CONTROL_PRICE = "ABOVE_CONTROL_PRICE";
}
