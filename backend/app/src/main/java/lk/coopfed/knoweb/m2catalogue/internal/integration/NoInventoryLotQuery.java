package lk.coopfed.knoweb.m2catalogue.internal.integration;

import java.util.UUID;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import org.springframework.stereotype.Component;

/**
 * Temporary M2-02 adapter until M5 provides real lot ownership.
 */
@Component
class NoInventoryLotQuery implements InventoryLotQuery {

    @Override
    public boolean hasAnyLot(UUID skuId) {
        return false;
    }
}
