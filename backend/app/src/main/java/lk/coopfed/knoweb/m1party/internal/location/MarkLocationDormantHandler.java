package lk.coopfed.knoweb.m1party.internal.location;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.LocationDormant;
import lk.coopfed.knoweb.m1party.api.MarkLocationDormant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACTIVE to DORMANT, with a reason (doc 21 section 4.3): the location stops trading and keeps
 * everything it has; it is never deleted. Doc 21 also asks for "no open till session", which is
 * an M6 query; M6 does not exist yet, so that guard waits for it (said in PROGRESS.md).
 * Permission prt.location.activate: doc 21 names prt.location.dormant, which 21A section 3.3's
 * catalogue does not have.
 */
@Service
@CommandHandler(permission = "prt.location.activate")
class MarkLocationDormantHandler implements Handles<MarkLocationDormant, UUID> {

    static final String AUDIT_DORMANT = "LOCATION_DORMANT";

    private final LocationRepository locations;
    private final AuditFacade audit;
    private final EventPublisher events;

    MarkLocationDormantHandler(LocationRepository locations, AuditFacade audit, EventPublisher events) {
        this.locations = locations;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(MarkLocationDormant command, ScopeContext scope) {

        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.location(locations, command.locationId());
        LocationGuards.requireStatus(location, Location.STATUS_ACTIVE, Location.STATUS_DORMANT);
        String reason = LocationGuards.reason(command.reasonCode(), command.reasonText());

        Map<String, Object> before = location.auditState();
        location.moveTo(Location.STATUS_DORMANT);
        locations.saveAndFlush(location);

        audit.record(
                AUDIT_DORMANT, Subject.of("location", location.getId()), before, location.auditState(), scope, reason);
        events.publish(new LocationDormant(
                location.getId(),
                location.ownerEntityId(),
                location.locationType(),
                command.reasonCode().strip()));

        return location.getId();
    }
}
