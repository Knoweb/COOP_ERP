package lk.coopfed.knoweb.m7customers.internal.ledger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Today for the society office: the calendar date in the business time zone. A repayment at the
 * office concerns no location, and an entity has no business date of its own (CR-19A-8,
 * kernel.api.BusinessDate). A till's posting takes the receipt's own business date instead.
 */
@Component
public class CustomersClock {

    private final Clock clock;
    private final ZoneId zone;

    CustomersClock(Clock clock, @Value("${coop-erp.business-timezone}") String zone) {
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
