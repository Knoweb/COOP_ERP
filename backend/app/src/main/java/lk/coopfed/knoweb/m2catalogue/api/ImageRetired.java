package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** image.retired.v1 (doc 22 section 5.3: "sku, barcode, thumb key"); the thumb key is null for a PENDING image. */
public record ImageRetired(UUID imageId, UUID skuId, String barcode, UUID ownerEntityId, String thumbKey)
        implements DomainEvent {

    public static final String TYPE = "image.retired.v1";
}
