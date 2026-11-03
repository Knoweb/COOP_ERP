package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Reorder control settings configured for an SKU at a location (PGL-M2-01).
 */
public record ReorderControlConfigured(
        UUID reorderControlId,
        UUID ownerEntityId,
        UUID locationId,
        UUID skuId,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        BigDecimal maxQty)
        implements DomainEvent {

    public static final String TYPE = "inventory.reorder_control_configured.v1";
}
