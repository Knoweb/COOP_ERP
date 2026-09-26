package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * What RegisterBatch answers: the batch the caller's line cites.
 *
 * @param created false when the (SKU, supplier, batch number) was already registered and that
 *                batch is returned (doc 22 section 3.7: "the GRN cites it")
 */
public record RegisteredBatch(UUID batchId, String batchNo, boolean synthetic, boolean created) {}
