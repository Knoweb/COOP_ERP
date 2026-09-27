package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a delivery note drop as the delivery events and the expected-drop snapshot row
 * carry it (24A section 7.3).
 *
 * @param batchId the batch picked, when the seller keyed or picked one; null otherwise
 */
public record DeliveryLineSummary(
        UUID lineId,
        int lineNo,
        UUID orderId,
        UUID orderLineId,
        UUID skuId,
        UUID batchId,
        String uomCode,
        BigDecimal dispatchedQty) {}
