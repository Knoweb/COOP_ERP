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
import lk.coopfed.knoweb.m1party.api.ActivateLocation;
import lk.coopfed.knoweb.m1party.api.LocationActivated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ONBOARDING to ACTIVE (21A section 6): the connectivity gate is met and, for a SHOP, a primary
 * till is named and at least one operator is assigned there. The primary till and the operator
 * are a shop's: a warehouse or an office has no till (doc 21 section 4.3, "primary till set for
 * SHOP").
 */
@Service
@CommandHandler(permission = "prt.location.activate")
class ActivateLocationHandler implements Handles<ActivateLocation, UUID> {

    static final String AUDIT_ACTIVATED = "LOCATION_ACTIVATED";

    private final LocationRepository locations;
    private final LocationFacts facts;
    private final AuditFacade audit;
    private final EventPublisher events;

    ActivateLocationHandler(
            LocationRepository locations, LocationFacts facts, AuditFacade audit, EventPublisher events) {
        this.locations = locations;
        this.facts = facts;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ActivateLocation command, ScopeContext scope) {

        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.location(locations, command.locationId());
        LocationGuards.requireStatus(location, Location.STATUS_ONBOARDING, Location.STATUS_ACTIVE);
        if (!location.connectivitySpecMet()) {
            throw new ProblemException("m1.location.connectivity_not_met", Map.of("locationId", location.getId()));
        }
        if (location.isShop() && !location.hasPrimaryTill()) {
            throw new ProblemException("m1.location.primary_till_required", Map.of("locationId", location.getId()));
        }
        if (location.isShop() && !facts.hasOperator(location.ownerEntityId(), location.getId())) {
            throw new ProblemException("m1.location.operator_required", Map.of("locationId", location.getId()));
        }

        Map<String, Object> before = location.auditState();
        location.moveTo(Location.STATUS_ACTIVE);
        locations.saveAndFlush(location);

        audit.record(AUDIT_ACTIVATED, Subject.of("location", location.getId()), before, location.auditState(), scope);
        events.publish(new LocationActivated(
                location.getId(), location.ownerEntityId(), location.locationType(), Location.STATUS_ONBOARDING));

        return location.getId();
    }
}
