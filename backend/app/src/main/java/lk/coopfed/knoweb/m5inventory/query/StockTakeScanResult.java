package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.util.UUID;

public record StockTakeScanResult(
        UUID scanId,
        UUID skuId,
        String skuCode,
        String skuNameEn,
        UUID batchId,
        String batchNo,
        String condition,
        BigDecimal quantity,
        BigDecimal totalCountedForLot,
        boolean wasDuplicate) {}
