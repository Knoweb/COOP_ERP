package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a stock lot or part of it is repriced (PGL-M2-03).
 */
public record StockRepriced(
        UUID repricingId,
        UUID ownerEntityId,
        UUID locationId,
        UUID stockLotId,
        UUID skuId,
        UUID batchId,
        BigDecimal qtyAffected,
        BigDecimal previousUnitCost,
        BigDecimal newUnitCost,
        BigDecimal valueAdjustment)
        implements DomainEvent {

    public static final String TYPE = "inventory.stock_repriced.v1";
}
