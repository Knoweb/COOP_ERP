package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * image.attached.v1 (doc 22 section 5.3: "sku, barcode, thumb key"), published after the
 * thumbnail (22A section 6). {@code replacedImageId} is the owner's earlier ACTIVE image of the
 * same SKU and barcode, retired by this one, or null.
 */
public record ImageAttached(
        UUID imageId, UUID skuId, String barcode, UUID ownerEntityId, String thumbKey, UUID replacedImageId)
        implements DomainEvent {

    public static final String TYPE = "image.attached.v1";
}
