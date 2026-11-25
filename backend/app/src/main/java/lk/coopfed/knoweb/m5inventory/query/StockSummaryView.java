package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregated stock balance summary for a SKU at a location (PGL-M2-10).
 *
 * <p>Surfaces explainable physical on-hand, reserved, available, incoming and outgoing in-transit,
 * and latest physical count variance quantities without manual reconciliation.
 */
public record StockSummaryView(
        UUID locationId,
        UUID skuId,
        String skuCode,
        String skuNameEn,
        String skuNameSi,
        String skuNameTa,
        String baseUomCode,
        UUID departmentId,
        String departmentNameEn,
        String departmentNameSi,
        String departmentNameTa,
        BigDecimal qtyOnHand,
        BigDecimal reservedQty,
        BigDecimal availableQty,
        BigDecimal inTransitIncomingQty,
        BigDecimal inTransitOutgoingQty,
        BigDecimal countedVarianceQty,
        BigDecimal countedVarianceValue,
        String latestCountStatus,
        Instant latestCountedAt,
        BigDecimal remainingStockQty,
        int lotCount,
        List<LotBalance> lots) {}
