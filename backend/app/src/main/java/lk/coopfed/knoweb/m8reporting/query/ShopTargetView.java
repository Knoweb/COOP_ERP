package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ShopTargetView(
        UUID targetId,
        UUID ownerEntityId,
        UUID locationId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal targetAmount,
        String currency,
        Instant createdAt,
        Instant updatedAt) {}
