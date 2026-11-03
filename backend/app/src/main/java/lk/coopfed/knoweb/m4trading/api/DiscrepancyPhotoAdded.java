package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** discrepancy.photo_added.v1: a photograph authorised for upload on the buyer's discrepancy (PENDING until verified). */
public record DiscrepancyPhotoAdded(UUID discrepancyId, UUID buyerEntityId, UUID sellerEntityId, UUID attachmentId)
        implements DomainEvent {

    public static final String TYPE = "discrepancy.photo_added.v1";
}
