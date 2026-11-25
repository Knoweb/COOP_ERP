package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record StockTakeReviewLine(
        UUID skuId,
        String skuCode,
        String skuNameEn,
        String skuNameSi,
        String skuNameTa,
        String barcode,
        UUID batchId,
        String batchNo,
        LocalDate expiryDate,
        String condition,
        BigDecimal expectedQty,
        BigDecimal countedQty,
        BigDecimal varianceQty,
        BigDecimal unitCost,
        BigDecimal varianceValue,
        String status) {}
