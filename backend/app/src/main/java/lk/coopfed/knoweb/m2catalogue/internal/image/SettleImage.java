package lk.coopfed.knoweb.m2catalogue.internal.image;

import java.util.UUID;

/**
 * What the thumbnail job decided about a PENDING image: ACTIVE with its thumbnail key, or FAILED
 * with the cause. Exactly one of the two is set. Internal to M2: only {@link ThumbnailJob} sends
 * it, in the OWN scope of the image's owner.
 */
record SettleImage(UUID imageId, String thumbKey, String failure) {

    static SettleImage activate(UUID imageId, String thumbKey) {
        return new SettleImage(imageId, thumbKey, null);
    }

    static SettleImage fail(UUID imageId, String failure) {
        return new SettleImage(imageId, null, failure);
    }
}
