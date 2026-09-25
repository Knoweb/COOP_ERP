package lk.coopfed.knoweb.m2catalogue.api;

import lk.coopfed.knoweb.kernel.api.DomainEvent;
import java.time.Instant;
import java.util.UUID;

public record BarcodeRegistered(
        UUID barcodeId,
        String barcode,
        String symbology,
        UUID skuId,
        String uom,
        UUID batchId,
        UUID ownerId,
        Instant occurredAt
) implements DomainEvent {
    public static final String TYPE = "catalogue.barcode.registered.v1";
}
