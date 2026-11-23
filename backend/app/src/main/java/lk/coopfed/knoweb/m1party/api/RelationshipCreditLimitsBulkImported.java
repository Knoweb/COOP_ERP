package lk.coopfed.knoweb.m1party.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when trading relationship credit limits are imported in bulk (CAT-14).
 */
public record RelationshipCreditLimitsBulkImported(UUID sellerEntityId, int importedCount, BigDecimal totalCreditLimit)
        implements DomainEvent {

    public static final String TYPE = "m1.relationship.credit_limits_bulk_imported.v1";
}
