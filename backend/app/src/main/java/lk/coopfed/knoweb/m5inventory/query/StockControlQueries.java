package lk.coopfed.knoweb.m5inventory.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The reads of stock control (25A section 5, "ListCounts / ListAdjustments / ListWriteOffs"; the
 * recipes and repacks): counts with their expectation, lines and adjustment; write-offs with their
 * lines, photographs and approval state; recipes; repacks; the negative lots to review. Read-only,
 * in the caller's scope under row-level security: a shop session sees its own shop.
 */
public interface StockControlQueries {

    /** The counts of a location, newest first. */
    List<CountView> counts(UUID locationId, ScopeContext scope);

    Optional<CountView> count(UUID taskId, ScopeContext scope);

    /** The write-offs of a location, newest first. */
    List<WriteOffView> writeOffs(UUID locationId, ScopeContext scope);

    Optional<WriteOffView> writeOff(UUID writeOffId, ScopeContext scope);

    /** The entity's recipes, active first, by name. */
    List<RecipeView> recipes(ScopeContext scope);

    /** The repacks done at a location, newest first. */
    List<RepackView> repacks(UUID locationId, ScopeContext scope);

    Optional<RepackView> repack(UUID repackId, ScopeContext scope);

    /** The lots of a location below zero (oversold at the till, doc 25 flow 6.2), oldest first. */
    List<NegativeLotView> negativeLots(UUID locationId, ScopeContext scope);

    /** One lot below zero; empty when it is not below zero or the scope does not see it. */
    Optional<NegativeLotView> negativeLot(UUID stockLotId, ScopeContext scope);
}
