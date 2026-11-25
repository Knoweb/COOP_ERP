package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event published when two SKUs are flagged as duplicate suspects (CAT-01, 22A section 6.1).
 */
public record DuplicateSuspected(
        UUID suspectId, UUID skuA, UUID skuB, BigDecimal score, List<String> reasons, UUID ownerEntityId)
        implements DomainEvent {

    public static final String TYPE = "duplicate.suspected.v1";
}
