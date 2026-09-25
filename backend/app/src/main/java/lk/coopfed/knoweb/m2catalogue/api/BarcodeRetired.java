package lk.coopfed.knoweb.m2catalogue.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

public record BarcodeRetired(UUID barcodeId, String reason, Instant occurredAt) implements DomainEvent {
    public static final String TYPE = "catalogue.barcode.retired.v1";
}
