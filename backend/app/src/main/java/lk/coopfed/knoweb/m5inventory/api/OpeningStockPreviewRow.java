package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Validated row preview for opening stock import review and re-check before commit (PGL-M2-06).
 */
public record OpeningStockPreviewRow(
        int lineNo,
        String sku,
        UUID skuId,
        String skuCode,
        String itemName,
        String baseUom,
        String batchNo,
        LocalDate expiryDate,
        BigDecimal printedMrp,
        LotCondition condition,
        BigDecimal qty,
        BigDecimal unitCost,
        BigDecimal extendedCost,
        boolean batchTracked,
        boolean expiryTracked,
        boolean hasPrintedMrp,
        String status,
        String error) {}
