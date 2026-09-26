package lk.coopfed.knoweb.m2catalogue.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One batch as the batch card shows it (22A section 8: "shows corrects/superseded chain"). */
public record BatchView(
        UUID batchId,
        UUID skuId,
        UUID supplierId,
        String batchNo,
        LocalDate manufactureDate,
        LocalDate expiryDate,
        BigDecimal printedMrp,
        UUID originDocumentId,
        boolean synthetic,
        UUID correctsBatchId,
        String status,
        UUID ownerEntityId,
        Instant createdAt) {}
