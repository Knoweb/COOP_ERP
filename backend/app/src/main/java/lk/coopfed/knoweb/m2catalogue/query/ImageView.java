package lk.coopfed.knoweb.m2catalogue.query;

import java.util.UUID;

/**
 * An image of a SKU.
 *
 * @param contentType the type the full image was uploaded as (image/jpeg, image/png ...), which
 *                    its presigned GET declares (wave 3, M1M2M3M5-14)
 */
public record ImageView(
        UUID imageId, String barcode, String status, String objectKeyFull, String objectKeyThumb, String contentType) {}
