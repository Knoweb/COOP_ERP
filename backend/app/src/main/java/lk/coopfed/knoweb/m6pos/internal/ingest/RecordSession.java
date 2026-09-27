package lk.coopfed.knoweb.m6pos.internal.ingest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A till session's open or close (doc 26 section 4.2; 26A section 8, SessionService), as the
 * till's {@code till_session.opened.v1} and {@code till_session.closed.v1} carry it.
 *
 * @param closing  false for the open (float), true for the close (counted, expected, variance)
 * @param at       opened_at or closed_at
 */
record RecordSession(
        UUID sessionId,
        boolean closing,
        UUID tillPositionId,
        UUID operatorUserId,
        LocalDate businessDate,
        Instant at,
        BigDecimal floatAmount,
        BigDecimal countedCash,
        BigDecimal expectedCash,
        BigDecimal variance) {}
