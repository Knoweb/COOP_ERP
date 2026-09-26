package lk.coopfed.knoweb.kernel.internal.businessdate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Gives a location its business-date row the moment M1 registers it (19A section 13: the
 * date comes "from the location's day-close state", so every location must have one). The
 * row starts on the calendar date; from then on only a day close moves it, and the cut-off
 * sees the location from its first night, whether or not a till ever closed a session there
 * (review of 26 Sep: a location without a row was never cut off).
 *
 * <p>The contract with M1 is the event payload alone ({@code registeredLocationId},
 * {@code ownerEntityId}, as {@code location.registered.v1} carries them); the kernel imports
 * nothing of M1. The consumer framework delivers the event in the OWN scope of the event's
 * entity, which is the scope the insert needs.
 */
@Component
class LocationRegisteredListener {

    private static final Logger log = LoggerFactory.getLogger(LocationRegisteredListener.class);

    static final String EVENT_TYPE = "location.registered.v1";
    static final String CONSUMER = "kernel-business-date";

    private final LocationBusinessDates dates;

    LocationRegisteredListener(LocationBusinessDates dates) {
        this.dates = dates;
    }

    @EventConsumer(types = EVENT_TYPE, consumer = CONSUMER)
    public void onLocationRegistered(JsonNode payload, ScopeContext scope) {
        JsonNode location = payload.path("registeredLocationId");
        JsonNode owner = payload.path("ownerEntityId");

        if (location.asText().isBlank() || owner.asText().isBlank()) {
            log.warn("location.registered.v1 without registeredLocationId or ownerEntityId: no business-date row");
            return;
        }

        dates.register(UUID.fromString(location.asText()), UUID.fromString(owner.asText()));
    }
}
