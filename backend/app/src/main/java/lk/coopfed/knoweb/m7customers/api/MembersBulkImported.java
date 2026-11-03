package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when members/customers are imported in bulk (CAT-10).
 */
public record MembersBulkImported(
        UUID importId, UUID ownerEntityId, int totalRows, int registeredCount, int updatedCount)
        implements DomainEvent {

    public static final String TYPE = "customers.bulk_imported.v1";
}
