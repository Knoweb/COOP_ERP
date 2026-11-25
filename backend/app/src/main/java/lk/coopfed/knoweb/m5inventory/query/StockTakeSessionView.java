package lk.coopfed.knoweb.m5inventory.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StockTakeSessionView(
        UUID sessionId,
        UUID ownerEntityId,
        UUID locationId,
        String scopeKind,
        List<UUID> skuIds,
        String status,
        Instant startedAt,
        UUID startedBy,
        int expectedLotsCount,
        int totalScansCount,
        BigDecimal totalCountedUnits) {}
