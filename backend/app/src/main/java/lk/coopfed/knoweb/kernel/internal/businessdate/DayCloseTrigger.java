package lk.coopfed.knoweb.kernel.internal.businessdate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DayClose;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The day-close trigger of 19A section 12: "DayCloseTrigger consumes till_session.closed.v1;
 * when every open session at a location has closed, it advances the location's business date
 * and publishes location.day_closed.v1".
 *
 * <p>Whether every session has closed is M6's knowledge, not the kernel's (the kernel reads
 * no module table). So the contract with M6 (26A, SessionHook) is in the event payload:
 * {@code openSessionsRemaining}, the number of sessions still open at that location after this
 * one closed. Zero closes the day; anything else, or a payload without the field, does nothing
 * here and leaves the day to the cut-off job.
 *
 * <p><b>Which location</b> (wave 2, TWK-23, decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (7)): the one the
 * envelope names, never the payload's. The event is a till's own, and the outbox row's
 * {@code location_id} is set by central from the device record (DeviceEventWriter); the consumer
 * framework hands it over as the scope's location. A payload {@code locationId} that differs is
 * logged and ignored, so a till at one shop can never close another shop's day. An event whose
 * envelope names no location does nothing. M6 publishing a central fact with the count it worked
 * out from its own tables ({@code location.sessions_closed.v1}) is M6's SessionHook ticket.
 */
@Component
class DayCloseTrigger {

    private static final Logger log = LoggerFactory.getLogger(DayCloseTrigger.class);

    static final String EVENT_TYPE = "till_session.closed.v1";
    static final String CONSUMER = "kernel-day-close";

    private final DayClose dayClose;

    DayCloseTrigger(DayClose dayClose) {
        this.dayClose = dayClose;
    }

    @EventConsumer(types = EVENT_TYPE, consumer = CONSUMER)
    public void onSessionClosed(JsonNode payload, ScopeContext scope) {
        JsonNode remaining = payload.path("openSessionsRemaining");
        if (!remaining.isInt()) {
            log.debug("till_session.closed.v1 without openSessionsRemaining: the cut-off closes the day");
            return;
        }
        UUID location = scope.locationId();
        if (location == null) {
            log.debug("till_session.closed.v1 whose envelope names no location: the cut-off closes the day");
            return;
        }
        JsonNode named = payload.path("locationId");
        if (named.isTextual() && !named.asText().equals(location.toString())) {
            log.warn(
                    "till_session.closed.v1 names location {} in its payload, but its envelope names {}: the"
                            + " envelope's is the one considered",
                    named.asText(),
                    location);
        }
        if (remaining.asInt() != 0) {
            return;
        }
        dayClose.close(location, scope);
    }
}
