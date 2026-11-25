package lk.coopfed.knoweb.m6pos.internal.ingest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RecordCashMovement(
        UUID movementId,
        UUID sessionId,
        UUID locationId,
        UUID tillPositionId,
        UUID deviceId,
        String kind,
        BigDecimal amount,
        Instant occurredAt,
        UUID operatorUserId,
        String reason,
        Long deviceSeq) {}
