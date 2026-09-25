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
import lk.coopfed.knoweb.m1party.api.ConfirmLocationConnectivity;
import lk.coopfed.knoweb.m1party.api.LocationUpdated;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that a location meets the connectivity specification, the gate doc 21 section 3.3 puts
 * in front of ACTIVE. It is a fact and not a toggle (21A section 8): it is confirmed once, and
 * the audit record is what says who confirmed it and when. Permission prt.location.activate,
 * because the confirmation is the first half of an activation.
 */
@Service
@CommandHandler(permission = "prt.location.activate")
class ConfirmLocationConnectivityHandler implements Handles<ConfirmLocationConnectivity, UUID> {

    static final String AUDIT_CONFIRMED = "LOCATION_CONNECTIVITY_CONFIRMED";

    private final LocationRepository locations;
    private final AuditFacade audit;
    private final EventPublisher events;

    ConfirmLocationConnectivityHandler(LocationRepository locations, AuditFacade audit, EventPublisher events) {
        this.locations = locations;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ConfirmLocationConnectivity command, ScopeContext scope) {

        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.location(locations, command.locationId());
        if (location.connectivitySpecMet()) {
            throw new ProblemException(
                    "m1.location.connectivity_already_confirmed", Map.of("locationId", location.getId()));
        }

        Map<String, Object> before = location.auditState();
        location.confirmConnectivity();
        locations.saveAndFlush(location);

        audit.record(AUDIT_CONFIRMED, Subject.of("location", location.getId()), before, location.auditState(), scope);
        events.publish(new LocationUpdated(
                location.getId(),
                location.ownerEntityId(),
                location.language(),
                TradingHours.fromJson(location.tradingHoursJson()),
                location.sizeBand(),
                location.connectivitySpecMet()));

        return location.getId();
    }
}
