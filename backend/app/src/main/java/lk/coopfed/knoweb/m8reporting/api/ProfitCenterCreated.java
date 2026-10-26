package lk.coopfed.knoweb.m8reporting.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a profit center is created.
 */
public record ProfitCenterCreated(
        UUID profitCenterId, UUID ownerEntityId, String code, String category, UUID locationId)
        implements DomainEvent {

    public static final String TYPE = "profit_center.created.v1";
}
