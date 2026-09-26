package lk.coopfed.knoweb.m2catalogue.internal.integration;

import java.util.UUID;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import org.springframework.stereotype.Component;

/**
 * Until M5 provides lots, nobody holds one: UpdateSku may change the tracking flags, and only the
 * Federation may correct a batch (CorrectBatchHandler). M5's implementation replaces this class
 * in the pull request that adds it.
 */
@Component
class NoInventoryLotQuery implements InventoryLotQuery {

    @Override
    public boolean hasAnyLot(UUID skuId) {
        return false;
    }

    @Override
    public boolean holdsLotOf(UUID batchId, UUID entityId) {
        return false;
    }
}
