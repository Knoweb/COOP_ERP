package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Detailed explainability drilldown for a SKU's stock position at a location (PGL-M2-10).
 *
 * <p>Details the specific lots, active pick-list reservations, in-transit transfers, and
 * stock-take count session behind the numbers.
 */
public record StockExplanationView(
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
        BigDecimal remainingStockQty,
        List<LotBalance> lots,
        List<ReservationDetail> reservations,
        List<InTransitDetail> incomingTransfers,
        List<InTransitDetail> outgoingTransfers,
        CountDetail latestCount) {

    public record ReservationDetail(
            UUID pickListId,
            UUID deliveryDocumentId,
            String deliveryDocNumber,
            BigDecimal reservedQty,
            String pickStatus,
            Instant reservedAt) {}

    public record InTransitDetail(
            UUID transferId,
            String documentNo,
            UUID locationId,
            String locationName,
            BigDecimal qty,
            Instant dispatchedAt,
            String status) {}

    public record CountDetail(
            UUID taskId,
            String scopeKind,
            String status,
            String outcome,
            BigDecimal expectedQty,
            BigDecimal countedQty,
            BigDecimal varianceQty,
            BigDecimal varianceValue,
            Instant startedAt,
            Instant submittedAt) {}
}
