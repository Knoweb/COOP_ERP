package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * batch.corrected.v1 (doc 22 section 5.3): the replacement batch and the batch it corrects. M5
 * re-points the lots of {@code correctsBatchId} to {@code batchId}; M3 re-evaluates its ceilings.
 */
public record BatchCorrected(
        UUID batchId,
        UUID correctsBatchId,
        UUID skuId,
        UUID supplierId,
        UUID ownerEntityId,
        String batchNo,
        LocalDate expiryDate,
        BigDecimal printedMrp,
        String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "batch.corrected.v1";
}
