package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when customer credit limits are imported in bulk (CAT-14).
 */
public record CustomerCreditLimitsBulkImported(UUID societyEntityId, int importedCount, BigDecimal totalCreditLimit)
        implements DomainEvent {

    public static final String TYPE = "customers.credit_limits_bulk_imported.v1";
}
