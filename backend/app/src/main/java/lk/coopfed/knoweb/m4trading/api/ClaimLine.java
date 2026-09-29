package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.UUID;

/** A line of a claim event: the claimed (or accepted, or returned) quantity of a GRN line's batch. */
public record ClaimLine(UUID claimLineId, UUID grnLineId, UUID skuId, UUID batchId, String uomCode, BigDecimal qty) {}
