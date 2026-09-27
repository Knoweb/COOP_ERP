package lk.coopfed.knoweb.m6pos.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The reads of M6 for the demo (26A section 10, "query/ PosQueries: sessions, ... receipts"),
 * read-only and in the caller's scope: a shop's own session sees its shop, an entity-wide user
 * every shop of the entity, the Federation view everything.
 */
public interface PosQueries {

    /** The receipts of a location, newest first, with their lines. */
    List<ReceiptView> receipts(UUID locationId, ScopeContext scope);

    /** The till sessions of a location, newest first, with their close when it arrived. */
    List<SessionView> sessions(UUID locationId, ScopeContext scope);

    record ReceiptView(
            UUID documentId,
            UUID locationId,
            UUID deviceId,
            UUID sessionId,
            String docNumberDisplay,
            Instant issuedAt,
            LocalDate businessDate,
            BigDecimal grossAmount,
            List<String> flags,
            List<Line> lines) {

        public record Line(
                int lineNo, UUID skuId, UUID batchId, BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}
    }

    record SessionView(
            UUID sessionId,
            UUID locationId,
            UUID tillPositionId,
            LocalDate businessDate,
            Instant openedAt,
            BigDecimal floatAmount,
            Instant closedAt,
            BigDecimal countedCash,
            BigDecimal expectedCash,
            BigDecimal variance) {}
}
