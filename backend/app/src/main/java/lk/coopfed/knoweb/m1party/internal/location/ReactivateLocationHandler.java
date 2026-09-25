package lk.coopfed.knoweb.m1party.internal.location;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.LocationActivated;
import lk.coopfed.knoweb.m1party.api.ReactivateLocation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DORMANT to ACTIVE (doc 21 section 4.3, Reactivate): the connectivity gate is met. Published as
 * location.activated.v1, as doc 21 records it, with the previous status DORMANT.
 */
@Service
@CommandHandler(permission = "prt.location.activate")
class ReactivateLocationHandler implements Handles<ReactivateLocation, UUID> {

    static final String AUDIT_REACTIVATED = "LOCATION_REACTIVATED";

    private final LocationRepository locations;
    private final AuditFacade audit;
    private final EventPublisher events;

    ReactivateLocationHandler(LocationRepository locations, AuditFacade audit, EventPublisher events) {
        this.locations = locations;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ReactivateLocation command, ScopeContext scope) {

        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.location(locations, command.locationId());
        LocationGuards.requireStatus(location, Location.STATUS_DORMANT, Location.STATUS_ACTIVE);
        if (!location.connectivitySpecMet()) {
            throw new ProblemException("m1.location.connectivity_not_met", Map.of("locationId", location.getId()));
        }

        Map<String, Object> before = location.auditState();
        location.moveTo(Location.STATUS_ACTIVE);
        locations.saveAndFlush(location);

        audit.record(AUDIT_REACTIVATED, Subject.of("location", location.getId()), before, location.auditState(), scope);
        events.publish(new LocationActivated(
                location.getId(), location.ownerEntityId(), location.locationType(), Location.STATUS_DORMANT));

        return location.getId();
    }
}
