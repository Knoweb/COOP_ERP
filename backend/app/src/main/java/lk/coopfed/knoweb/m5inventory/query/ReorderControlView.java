package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Reorder control setting and current stock status for an SKU at a location (PGL-M2-01).
 */
public record ReorderControlView(
        UUID reorderControlId,
        UUID ownerEntityId,
        UUID locationId,
        UUID skuId,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        BigDecimal maxQty,
        boolean alertTriggered,
        Instant lastAlertedAt,
        BigDecimal lastAlertQty,
        BigDecimal currentOnHandQty,
        BigDecimal currentAvailableQty) {}
