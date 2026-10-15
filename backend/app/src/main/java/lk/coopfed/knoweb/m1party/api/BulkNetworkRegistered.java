package lk.coopfed.knoweb.m1party.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a network of shops, till positions, users and devices is registered.
 */
public record BulkNetworkRegistered(UUID entityId, int shopsCount, int positionsCount, int usersCount, int devicesCount)
        implements DomainEvent {

    public static final String TYPE = "m1.network.bulk_registered.v1";
}
