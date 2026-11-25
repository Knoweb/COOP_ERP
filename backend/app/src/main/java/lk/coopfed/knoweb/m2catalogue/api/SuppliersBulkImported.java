package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Emitted when suppliers are bulk-imported in COMMIT mode (PGL-M2-05).
 */
public record SuppliersBulkImported(
        UUID importId, UUID ownerEntityId, int totalRows, int createdCount, int skippedCount, String mode)
        implements DomainEvent {

    public static final String TYPE = "suppliers.bulk_imported.v1";
}
