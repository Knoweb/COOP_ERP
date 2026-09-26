package lk.coopfed.knoweb.m2catalogue.query;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The filter of ListBatches (22A section 5: skuId, batchNo?, expiringBefore?). The SKU is
 * required: batches run to millions a year (doc 22 section 9.2) and are read per item. The page
 * size is bounded by the slice (limit 1 to 200, default 50), as SkuFilter's.
 */
public record BatchFilter(UUID skuId, String batchNo, LocalDate expiringBefore, Integer limit) {

    public int normalizedLimit() {
        if (limit == null) {
            return 50;
        }
        return Math.max(1, Math.min(limit, 200));
    }
}
