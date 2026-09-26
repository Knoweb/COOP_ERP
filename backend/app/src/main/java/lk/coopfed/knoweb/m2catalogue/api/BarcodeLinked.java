package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** barcode.linked.v1 (doc 22 section 5.3): the batch the code now identifies. */
public record BarcodeLinked(
        UUID skuId, UUID ownerEntityId, String barcode, String symbology, String uomCode, UUID batchId)
        implements DomainEvent {

    public static final String TYPE = "barcode.linked.v1";
}
