package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** barcode.registered.v1 (doc 22 section 5.3: barcode, symbology, sku, uom, batch). */
public record BarcodeRegistered(
        UUID skuId, UUID ownerEntityId, String barcode, String symbology, String uomCode, UUID batchId)
        implements DomainEvent {

    public static final String TYPE = "barcode.registered.v1";
}
