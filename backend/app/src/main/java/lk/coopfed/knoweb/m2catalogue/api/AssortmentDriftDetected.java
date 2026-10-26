package lk.coopfed.knoweb.m2catalogue.api;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Domain event emitted or applied when assortment drift is reconciled or dynamically maintained
 * (doc 22 section 3.8; 22A section 7.2).
 */
public record AssortmentDriftDetected(
        UUID locationId, UUID skuId, List<String> reasons, boolean sellThrough, UUID ownerEntityId, Action action)
        implements DomainEvent {

    public static final String TYPE = "catalogue.assortment.drift.v1";

    public enum Action {
        UPSERT,
        DELETE
    }
}
