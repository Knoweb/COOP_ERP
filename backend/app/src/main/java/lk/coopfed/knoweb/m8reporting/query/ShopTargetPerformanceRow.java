package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ShopTargetPerformanceRow(
        UUID targetId,
        UUID locationId,
        String locationCode,
        String locationName,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal targetAmount,
        BigDecimal actualAmount,
        BigDecimal varianceAmount,
        BigDecimal achievementPercent,
        String currency,
        Instant createdAt,
        Instant updatedAt) {}
