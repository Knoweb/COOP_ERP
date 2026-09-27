package lk.coopfed.knoweb.m2catalogue.internal.integration;

import java.util.UUID;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M2's answer for M5 until M5 exists, read from the batches M2 registered. A batch is registered
 * only by the command that creates a lot of it (M4's GRN confirmation, M5's repack: doc 22
 * section 3.7), so the entity that registered a batch, or corrected it, is the one M5 will name
 * as holding a lot of it the moment the lot exists; and a SKU with a batch is a SKU with a lot.
 *
 * <p>Decided 27 September 2026 on the architect's delegation (pull request #135 asked it): the
 * entity that registered a local-supply batch corrects its own mis-keyed MRP or expiry before M5,
 * as 22A section 6 lets a lot holder do ("caller owns a lot of the batch or F"), instead of asking
 * the Federation to. The answer is wider than M5's only once stock moves on, and no stock moves
 * before M5. M5's implementation replaces this class in the pull request that adds it.
 */
@Component
class RegistrationLotQuery implements InventoryLotQuery {

    private final JdbcTemplate jdbc;

    RegistrationLotQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** UpdateSku: once a batch of the SKU is registered, its base unit and tracking flags stay. */
    @Override
    public boolean hasAnyLot(UUID skuId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from catalogue.batch where sku_id = ?)", Boolean.class, skuId));
    }

    /**
     * CorrectBatch: the entity registered the batch, or a batch it replaces. The chain is walked
     * back through corrects_batch_id, so the registering entity still corrects after another
     * correction made the replacement someone else's row. A batch is read by every scope.
     */
    @Override
    public boolean holdsLotOf(UUID batchId, UUID entityId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                with recursive chain (batch_id, corrects_batch_id, owner_entity_id) as (
                    select batch_id, corrects_batch_id, owner_entity_id
                      from catalogue.batch
                     where batch_id = ?
                    union
                    select b.batch_id, b.corrects_batch_id, b.owner_entity_id
                      from catalogue.batch b
                      join chain c on b.batch_id = c.corrects_batch_id
                )
                select exists (select 1 from chain where owner_entity_id = ?)
                """,
                Boolean.class,
                batchId,
                entityId));
    }
}
