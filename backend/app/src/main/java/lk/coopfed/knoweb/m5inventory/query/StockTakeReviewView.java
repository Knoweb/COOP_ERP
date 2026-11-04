package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record StockTakeReviewView(
        UUID sessionId,
        UUID locationId,
        String status,
        int totalExpectedLots,
        int totalCountedLots,
        int matchedLots,
        int discrepantLots,
        int uncountedLots,
        BigDecimal totalVarianceQty,
        BigDecimal totalVarianceValue,
        List<StockTakeReviewLine> lines) {}
