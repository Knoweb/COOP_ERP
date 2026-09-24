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
 * {@code locationId}, and {@code openSessionsRemaining}, the number of sessions still open at
 * that location after this one closed. Zero closes the day; anything else, or a payload
 * without the field, does nothing here and leaves the day to the cut-off job.
 *
 * <p>The consumer framework delivers the event in the OWN scope of the event's entity, which
 * is the scope the close needs.
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
        JsonNode location = payload.path("locationId");

        if (!remaining.isInt() || location.isMissingNode() || location.asText().isBlank()) {
            log.debug("till_session.closed.v1 without openSessionsRemaining or locationId: the cut-off closes the day");
            return;
        }

        if (remaining.asInt() != 0) {
            return;
        }

        dayClose.close(UUID.fromString(location.asText()), scope);
    }
}
