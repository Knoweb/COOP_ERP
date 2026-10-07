package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The questions M2's guards ask of M5 (22A section 6). M2 cannot call M5 before M5 exists, so M2
 * publishes the question and M5 answers it by implementing this interface
 * ({@code m5inventory.internal.availability.LotQuestionsForCatalogue}, since M5-04).
 *
 * <p>Both questions are about the caller: the answer comes from the caller's scope (an OWN class,
 * the scope entity), never from an entity named as a parameter, so a session learns nothing about
 * another entity's lots (wave 2, RLS-12; {@code
 * docs/progress/deviations/2026-10-06-wave2-cross-tenant-functions.md} (2)).
 */
public interface InventoryLotQuery {

    /** UpdateSku: a SKU's base unit and tracking flags cannot change once a lot exists, anywhere. */
    boolean hasAnyLot(UUID skuId);

    /** CorrectBatch: "caller owns a lot of the batch (M5 query) or F": the caller is the scope's entity. */
    boolean holdsLotOf(UUID batchId, ScopeContext scope);
}
