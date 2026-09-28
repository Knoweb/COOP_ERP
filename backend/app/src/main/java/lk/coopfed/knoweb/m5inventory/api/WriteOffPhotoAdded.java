package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A photograph was authorised for upload against a draft write-off. */
public record WriteOffPhotoAdded(UUID writeOffId, UUID ownerEntityId, UUID attachmentId) implements DomainEvent {

    public static final String TYPE = "writeoff.photo_added.v1";
}
