package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** claim.photo_added.v1: a photograph authorised for upload on the buyer's claim (PENDING until verified). */
public record ClaimPhotoAdded(UUID claimId, UUID buyerEntityId, UUID sellerEntityId, UUID attachmentId)
        implements DomainEvent {

    public static final String TYPE = "claim.photo_added.v1";
}
