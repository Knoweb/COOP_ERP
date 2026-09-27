package lk.coopfed.knoweb.m6pos.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * Central recorded a till session's open or close (26A section 10, SessionHook: "publish
 * till_session.*.v1"). Named apart from the till's own {@code till_session.opened.v1} and
 * {@code till_session.closed.v1}, which M6 consumes, so M6 never consumes what it publishes.
 *
 * @param status OPEN or CLOSED
 */
public record TillSessionRecorded(UUID sessionId, UUID ownerEntityId, UUID locationId, String status)
        implements DomainEvent {

    public static final String TYPE = "till_session.recorded.v1";
}
