package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * image.pending.v1: an image was attached and waits for its upload. Not in doc 22 section 5.3,
 * whose image.attached.v1 comes after the thumbnail; every command publishes its event
 * (AGENTS.md), and nothing consumes this one yet.
 */
public record ImagePending(UUID imageId, UUID skuId, String barcode, UUID ownerEntityId) implements DomainEvent {

    public static final String TYPE = "image.pending.v1";
}
