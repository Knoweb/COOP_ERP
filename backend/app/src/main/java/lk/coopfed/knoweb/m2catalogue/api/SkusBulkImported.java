package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when SKUs are bulk-imported into the catalogue (CAT-01).
 */
public record SkusBulkImported(
        UUID importId, UUID ownerEntityId, int rowCount, int acceptedCount, int suspectCount, String mode)
        implements DomainEvent {

    public static final String TYPE = "skus.bulk_imported.v1";
}
