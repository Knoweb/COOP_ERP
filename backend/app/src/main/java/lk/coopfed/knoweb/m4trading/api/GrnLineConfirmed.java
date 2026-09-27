package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of a confirmed GRN as grn.confirmed.v1 carries it (24A section 6.1: "lines(batch,
 * qty, damaged, unitCost)"). M5 creates the receiver's lot of {@code batchId}: {@code receivedQty
 * - damagedQty} as GOOD and {@code damagedQty} as DAMAGED, at {@code unitCost}.
 *
 * @param expectedQty from the delivery note's drop; null for a local supply
 * @param unitCost    the trade price of the relationship for a delivery, the keyed cost for a local supply
 */
public record GrnLineConfirmed(
        UUID lineId,
        int lineNo,
        UUID skuId,
        UUID batchId,
        String batchNo,
        LocalDate expiryDate,
        BigDecimal printedMrp,
        String uomCode,
        BigDecimal expectedQty,
        BigDecimal receivedQty,
        BigDecimal damagedQty,
        BigDecimal unitCost) {}
