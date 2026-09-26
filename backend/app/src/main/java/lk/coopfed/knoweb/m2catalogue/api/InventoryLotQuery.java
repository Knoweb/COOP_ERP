package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * The questions M2's guards ask of M5 (22A section 6). M2 cannot call M5 before M5 exists, so M2
 * publishes the question and M5 answers it by implementing this interface; until then
 * {@code internal.integration.NoInventoryLotQuery} answers "no lots".
 */
public interface InventoryLotQuery {

    /** UpdateSku: a SKU's base unit and tracking flags cannot change once a lot exists. */
    boolean hasAnyLot(UUID skuId);

    /** CorrectBatch: "caller owns a lot of the batch (M5 query) or F". */
    boolean holdsLotOf(UUID batchId, UUID entityId);
}
