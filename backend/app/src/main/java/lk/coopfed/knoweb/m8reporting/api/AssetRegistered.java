package lk.coopfed.knoweb.m8reporting.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when a fixed asset is registered (Polgolla M1-04).
 *
 * <p>Sensitive fields such as human names are omitted per kernel outbox policy.
 */
public record AssetRegistered(UUID assetId, UUID ownerEntityId, String assetCode, String assetType, UUID locationId)
        implements DomainEvent {

    public static final String TYPE = "asset.registered.v1";
}
