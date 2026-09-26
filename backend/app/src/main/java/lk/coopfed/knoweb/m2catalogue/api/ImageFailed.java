package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * image.failed.v1: the upload never came, came with another hash or larger than allowed, or is
 * not an image the thumbnail job reads. Not in doc 22 section 5.3 (M2-06, module README).
 */
public record ImageFailed(UUID imageId, UUID skuId, UUID ownerEntityId, String cause) implements DomainEvent {

    public static final String TYPE = "image.failed.v1";
}
