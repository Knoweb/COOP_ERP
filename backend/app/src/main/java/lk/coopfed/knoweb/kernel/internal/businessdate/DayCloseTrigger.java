package lk.coopfed.knoweb.kernel.internal.businessdate;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DayClose;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.LocationSessionsClosed;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The day-close trigger of 19A section 12: "DayCloseTrigger consumes location.sessions_closed.v1;
 * when every open session at a location has closed, it advances the location's business date
 * and publishes location.day_closed.v1".
 *
 * <p>Whether every session has closed is M6's knowledge, not the kernel's (the kernel reads
 * no module table). M6 publishes the central fact {@code location.sessions_closed.v1}
 * ({@link LocationSessionsClosed}) with the count it worked out from its own tables (26A,
 * SessionHook, wave 2 TWK-23, C-02d).
 *
 * <p><b>Which location</b> (wave 2, TWK-23, decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (7)): the one the
 * envelope names, never the payload's. The outbox row's {@code location_id} is set by central
 * from the device record (DeviceEventWriter) or M6's handler; the consumer framework hands it
 * over as the scope's location. A payload {@code locationId} or {@code closedLocationId} that
 * differs is logged and ignored, so an untrusted payload can never redirect another shop's day
 * close. An event whose envelope names no location does nothing.
 *
 * <p><b>Atomic date guard</b>: if the event carries {@code businessDate}, it is passed to
 * {@link DayClose#closeForDate(UUID, LocalDate, ScopeContext)} so that the guard against past,
 * future, delayed, or already-advanced dates is evaluated atomically under the location's lock.
 */
@Component
class DayCloseTrigger {

    private static final Logger log = LoggerFactory.getLogger(DayCloseTrigger.class);

    static final String EVENT_TYPE = LocationSessionsClosed.TYPE;
    static final String CONSUMER = "kernel-day-close";

    private final DayClose dayClose;

    DayCloseTrigger(DayClose dayClose) {
        this.dayClose = dayClose;
    }

    @EventConsumer(types = EVENT_TYPE, consumer = CONSUMER)
    public void onSessionsClosed(JsonNode payload, ScopeContext scope) {
        UUID location = scope != null ? scope.locationId() : null;
        if (location == null) {
            log.debug("location.sessions_closed.v1 whose envelope names no location: the cut-off closes the day");
            return;
        }

        JsonNode named = payload.path("closedLocationId");
        if (!named.isTextual()) {
            named = payload.path("locationId");
        }
        if (named.isTextual() && !named.asText().equals(location.toString())) {
            log.warn(
                    "location.sessions_closed.v1 names location {} in its payload, but its envelope names {}: the"
                            + " envelope's is the one considered",
                    named.asText(),
                    location);
        }

        LocalDate expectedDate = null;
        if (payload.hasNonNull("businessDate")) {
            try {
                expectedDate = LocalDate.parse(payload.path("businessDate").asText());
            } catch (Exception e) {
                log.warn("location.sessions_closed.v1 with unparseable businessDate: ignored", e);
                return;
            }
        }

        dayClose.closeForDate(location, expectedDate, scope);
    }
}
