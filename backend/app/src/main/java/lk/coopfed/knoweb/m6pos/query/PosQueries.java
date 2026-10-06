package lk.coopfed.knoweb.m6pos.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The reads of M6 (26A section 10, "query/ PosQueries: sessions, ... receipts"), read-only and in
 * the caller's scope: a shop's own session sees its shop, an entity-wide user every shop of the
 * entity, the Federation view everything.
 *
 * <p>Wave 2, M6-08 and M6-10 (decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (4)): the lists are
 * paged by an opaque cursor over (time, id), newest first, one business day at a time, with the
 * lines and tenders of a page read in two queries; one receipt and one session have reads of
 * their own.
 */
public interface PosQueries {

    /**
     * One page of a location's receipts, newest first, with their lines and tenders.
     *
     * @throws lk.coopfed.knoweb.kernel.api.ProblemException {@code request.malformed} for a cursor
     *     that cannot be read
     */
    Page<ReceiptView> receipts(ReceiptFilter filter, ScopeContext scope);

    /** One receipt, or empty when there is none or the caller's scope may not see it. */
    Optional<ReceiptView> receipt(UUID documentId, ScopeContext scope);

    /**
     * One page of a location's till sessions, newest first, with their close when it arrived; a
     * close whose open never arrived is listed with the open's fields empty.
     */
    Page<SessionView> sessions(SessionFilter filter, ScopeContext scope);

    /** One session, or empty when there is none or the caller's scope may not see it. */
    Optional<SessionView> session(UUID sessionId, ScopeContext scope);

    /**
     * @param locationId   the shop; required
     * @param businessDate the business day (issued within its bounds in the business time zone);
     *                     null for every day
     * @param flaggedOnly  true for the receipts central flagged only
     * @param cursor       the previous page's {@link Page#nextCursor}, or null for the first page
     * @param limit        the page size; null for the default, never more than the configured
     *                     maximum
     */
    record ReceiptFilter(UUID locationId, LocalDate businessDate, boolean flaggedOnly, String cursor, Integer limit) {}

    /** As {@link ReceiptFilter}; a session's time is its open, or its close when the open is missing. */
    record SessionFilter(UUID locationId, LocalDate businessDate, String cursor, Integer limit) {}

    /** @param nextCursor the next page's cursor, or null on the last page */
    record Page<T>(List<T> items, String nextCursor) {}

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
            List<Line> lines,
            UUID tillPositionId,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            List<Tender> tenders) {

        public record Line(
                int lineNo, UUID skuId, UUID batchId, BigDecimal qty, BigDecimal unitPrice, BigDecimal lineTotal) {}

        /** How the customer paid: CASH, CARD ... as the till reported it, in the till's order. */
        public record Tender(int seq, String kind, BigDecimal amount) {}
    }

    /** A session; the open's fields (position, business date, opened at, float) are null for an orphan close. */
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
