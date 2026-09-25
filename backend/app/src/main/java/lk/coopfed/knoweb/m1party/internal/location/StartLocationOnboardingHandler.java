package lk.coopfed.knoweb.m1party.internal.location;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.LocationOnboardingStarted;
import lk.coopfed.knoweb.m1party.api.StartLocationOnboarding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLANNED to ONBOARDING (doc 21 section 4.3). Doc 21 guards it with "device staged"; devices are
 * M1-06 and the staging record doc 31's, so the guard waits for them (said in PROGRESS.md).
 * Permission prt.location.activate: doc 21 names prt.location.onboard, which 21A section 3.3's
 * catalogue does not have.
 */
@Service
@CommandHandler(permission = "prt.location.activate")
class StartLocationOnboardingHandler implements Handles<StartLocationOnboarding, UUID> {

    static final String AUDIT_ONBOARDING = "LOCATION_ONBOARDING";

    private final LocationRepository locations;
    private final AuditFacade audit;
    private final EventPublisher events;

    StartLocationOnboardingHandler(LocationRepository locations, AuditFacade audit, EventPublisher events) {
        this.locations = locations;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(StartLocationOnboarding command, ScopeContext scope) {

        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.location(locations, command.locationId());
        LocationGuards.requireStatus(location, Location.STATUS_PLANNED, Location.STATUS_ONBOARDING);

        Map<String, Object> before = location.auditState();
        location.moveTo(Location.STATUS_ONBOARDING);
        locations.saveAndFlush(location);

        audit.record(AUDIT_ONBOARDING, Subject.of("location", location.getId()), before, location.auditState(), scope);
        events.publish(
                new LocationOnboardingStarted(location.getId(), location.ownerEntityId(), location.locationType()));

        return location.getId();
    }
}
