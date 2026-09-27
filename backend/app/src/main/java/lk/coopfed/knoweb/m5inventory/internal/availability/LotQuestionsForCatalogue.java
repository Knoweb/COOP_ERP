package lk.coopfed.knoweb.m5inventory.internal.availability;

import java.util.UUID;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M5's answer to the questions M2's guards ask (22A section 6; 25A section 10, M5-04: "replace the
 * M2/M3/M4 stubs"). M2 publishes {@link InventoryLotQuery} and cannot call M5 (M5 depends on M2);
 * M5 implements it, and M2's own stand-in (answered from the batches it registered) is gone.
 *
 * <p>Both questions are asked inside M2's command, in its caller's scope, about lots that scope
 * need not read (a Federation edit of a shared SKU a society holds), so each is answered by a
 * function of m5inventory V0002 that returns yes or no and shows no lot.
 */
@Component
class LotQuestionsForCatalogue implements InventoryLotQuery {

    private final JdbcTemplate jdbc;

    LotQuestionsForCatalogue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** UpdateSku: a lot of the SKU exists anywhere, at any quantity. */
    @Override
    public boolean hasAnyLot(UUID skuId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select inventory.sku_has_lot(?)", Boolean.class, skuId));
    }

    /** CorrectBatch: the entity holds (or held) a lot of the batch. */
    @Override
    public boolean holdsLotOf(UUID batchId, UUID entityId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select inventory.entity_holds_lot_of(?, ?)", Boolean.class, batchId, entityId));
    }
}
