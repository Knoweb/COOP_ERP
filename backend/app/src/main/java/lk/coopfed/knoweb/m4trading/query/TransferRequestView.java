package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transfer request (doc 24 section 4.7) with the society's decision and, once M5 issued it, the
 * transfer that fulfils it ({@code transferId}; its status IN_TRANSIT or RECEIVED, from M5).
 * {@code status} is REQUESTED, APPROVED or REJECTED.
 */
public record TransferRequestView(
        UUID requestId,
        UUID ownerEntityId,
        UUID fromLocationId,
        UUID toLocationId,
        String status,
        String reason,
        UUID requestedBy,
        Instant requestedAt,
        String rejectReason,
        UUID decidedBy,
        Instant decidedAt,
        UUID transferId,
        String transferStatus,
        List<Line> lines) {

    public record Line(UUID lineId, UUID skuId, BigDecimal qty) {}
}
