package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A lot that is currently eligible to replace one FEFO-suggested pick. */
public record PickReplacementCandidate(UUID stockLotId, UUID batchId, LocalDate expiryDate, BigDecimal availableQty) {}
