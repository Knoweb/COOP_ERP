package lk.coopfed.knoweb.m4trading.internal.document;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentStateHistoryRecord;
import lk.coopfed.knoweb.kernel.api.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Today for an entity-level trading document (an order, a delivery note, an invoice): the calendar
 * date in the business time zone, since an entity has no business date of its own (CR-19A-8,
 * kernel.api.BusinessDate). A document of one location (a GRN) asks BusinessDate instead.
 */
@Component
public class TradingClock {

    private final Clock clock;
    private final ZoneId zone;

    TradingClock(Clock clock, @Value("${coop-erp.business-timezone}") String zone) {
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public ZoneId zone() {
        return zone;
    }

    /** A state history row of a trading document, now, by the caller. */
    public DocumentStateHistoryRecord transition(
            UUID documentId, String from, String to, UUID actorUserId, String reasonCode, String reasonText) {
        Instant at = now();
        return new DocumentStateHistoryRecord(
                Ids.next(),
                documentId,
                from,
                to,
                at,
                LocalDateTime.ofInstant(at, zone),
                actorUserId,
                null,
                reasonCode,
                reasonText);
    }
}
