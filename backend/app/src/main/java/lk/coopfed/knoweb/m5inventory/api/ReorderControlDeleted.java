package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a reorder control is removed for an SKU at a location (PGL-M2-01).
 *
 * @param reorderControlId the identifier of the deleted reorder control
 * @param ownerEntityId the entity that owned the control
 * @param locationId the location of the control
 * @param skuId the item of the control
 */
public record ReorderControlDeleted(UUID reorderControlId, UUID ownerEntityId, UUID locationId, UUID skuId)
        implements DomainEvent {

    public static final String TYPE = "inventory.reorder_control_deleted.v1";
}
