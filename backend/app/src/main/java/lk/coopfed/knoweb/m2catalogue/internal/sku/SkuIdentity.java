package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.UUID;

/**
 * What the other aggregates of the module (conversions, barcodes) need to know about a SKU: whose
 * it is, whether it is active, and the unit facts their guards test. Read-only; the SKU itself
 * stays in this package.
 */
public record SkuIdentity(UUID skuId, UUID ownerEntityId, String status, String baseUomCode, boolean soldByWeight) {

    /** LOCAL or SHARED (22A section 6, RegisterBarcode: "sku active"). */
    public boolean active() {
        return Sku.LOCAL.equals(status) || Sku.SHARED.equals(status);
    }
}
