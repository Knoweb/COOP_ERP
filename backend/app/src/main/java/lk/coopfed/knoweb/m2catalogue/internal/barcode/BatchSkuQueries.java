package lk.coopfed.knoweb.m2catalogue.internal.barcode;

import java.util.UUID;

public interface BatchSkuQueries {
    boolean isBatchOfSku(UUID batchId, UUID skuId);
}
