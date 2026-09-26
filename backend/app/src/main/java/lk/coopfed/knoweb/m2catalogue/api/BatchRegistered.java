package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** batch.registered.v1 (doc 22 section 5.3): M3 compares the MRP with its list; M5 and M8 read it. */
public record BatchRegistered(
        UUID batchId,
        UUID skuId,
        UUID supplierId,
        UUID ownerEntityId,
        String batchNo,
        LocalDate manufactureDate,
        LocalDate expiryDate,
        BigDecimal printedMrp,
        boolean synthetic,
        UUID originDocumentId)
        implements DomainEvent {

    public static final String TYPE = "batch.registered.v1";
}
