package lk.coopfed.knoweb.m1party.internal.location;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.LocationUpdated;
import lk.coopfed.knoweb.m1party.api.UpdateLocation;
import lk.coopfed.knoweb.m1party.internal.entity.EntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the descriptive facts of a location: names, address, district, position on the map,
 * language, trading hours, size band (doc 21 sections 3.3 and 7; the "hours editor" of the asset
 * register, 21A section 8). Permission: the one that registers locations, prt.location.register,
 * because 21A section 3.3 names no separate code for an update.
 */
@Service
@CommandHandler(permission = "prt.location.register")
class UpdateLocationHandler implements Handles<UpdateLocation, UUID> {

    static final String AUDIT_UPDATED = "LOCATION_UPDATED";

    private final LocationRepository locations;
    private final EntityRepository entities;
    private final AuditFacade audit;
    private final EventPublisher events;

    UpdateLocationHandler(
            LocationRepository locations, EntityRepository entities, AuditFacade audit, EventPublisher events) {
        this.locations = locations;
        this.entities = entities;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(UpdateLocation command, ScopeContext scope) {

        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.location(locations, command.locationId());
        String tradingHours = TradingHours.toJson(command.tradingHours());

        String language = command.language();
        if (language == null) {
            language = entities.findById(location.ownerEntityId())
                    .map(owner -> owner.defaultLanguage())
                    .orElse(location.language());
        }

        Map<String, Object> before = location.auditState();
        location.update(new Location.Facts(
                command.nameEn(),
                command.nameSi(),
                command.nameTa(),
                command.address(),
                command.district(),
                command.geoLat(),
                command.geoLng(),
                language,
                tradingHours,
                command.sizeBand()));
        locations.saveAndFlush(location);

        audit.record(AUDIT_UPDATED, Subject.of("location", location.getId()), before, location.auditState(), scope);
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
