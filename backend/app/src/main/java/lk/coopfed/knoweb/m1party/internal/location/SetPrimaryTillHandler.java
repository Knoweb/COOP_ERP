package lk.coopfed.knoweb.m1party.internal.location;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.LocationPrimaryChanged;
import lk.coopfed.knoweb.m1party.api.SetPrimaryTill;
import lk.coopfed.knoweb.m1party.internal.entity.Entity;
import lk.coopfed.knoweb.m1party.internal.entity.EntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, SetPrimaryTill: the position belongs to the location and is ACTIVE; a reason.
 * The primary till holds the shop's location series (doc 21 A-I7: a shop issues location-series
 * documents only once it has one): when a device is assigned to the new position, the counters
 * move to it through the kernel (NumberingService.holderChange, which publishes
 * series.holder_changed.v1). With no device there yet, the counters stay where they are; M1-06's
 * AssignDeviceToPosition moves them when the primary till gets its device (21A section 6.1).
 */
@Service
@CommandHandler(permission = "prt.location.primary", requiresMfa = true)
class SetPrimaryTillHandler implements Handles<SetPrimaryTill, UUID> {

    static final String AUDIT_PRIMARY_CHANGED = "LOCATION_PRIMARY_CHANGED";

    private final LocationRepository locations;
    private final TillPositionRepository positions;
    private final EntityRepository entities;
    private final LocationFacts facts;
    private final SeriesHooks series;
    private final AuditFacade audit;
    private final EventPublisher events;

    SetPrimaryTillHandler(
            LocationRepository locations,
            TillPositionRepository positions,
            EntityRepository entities,
            LocationFacts facts,
            SeriesHooks series,
            AuditFacade audit,
            EventPublisher events) {
        this.locations = locations;
        this.positions = positions;
        this.entities = entities;
        this.facts = facts;
        this.series = series;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SetPrimaryTill command, ScopeContext scope) {

        // 1. the position belongs to the location.
        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.location(locations, command.locationId());
        TillPosition position = LocationGuards.position(positions, command.tillPositionId());
        if (!position.locationId().equals(location.getId())) {
            throw new ProblemException(
                    "m1.position.not_in_location",
                    Map.of("tillPositionId", position.getId(), "locationId", location.getId()));
        }
        // 2. the position is ACTIVE.
        if (!position.isActive()) {
            throw new ProblemException("m1.position.not_active", Map.of("tillPositionId", position.getId()));
        }
        // 3. a reason.
        String reason = LocationGuards.reason(command.reasonCode(), command.reasonText());
        if (position.getId().equals(location.primaryTillPositionId())) {
            throw new ProblemException("m1.location.primary_unchanged", Map.of("tillPositionId", position.getId()));
        }

        // Mutation: the flag, then the counters of the location series.
        Map<String, Object> before = location.auditState();
        UUID previous = location.primaryTillPositionId();
        location.namePrimaryTill(position.getId());
        locations.saveAndFlush(location);

        String entityCode = entities.findById(location.ownerEntityId())
                .map(Entity::entityCode)
                .orElseThrow(() -> new IllegalStateException("The owner of location " + location.getId()
                        + " is not visible in the scope that sees the location"));
        List<UUID> locationSeries = series.registerLocationSeries(location, entityCode, scope);
        Optional<UUID> device = facts.deviceAt(position.getId());
        device.ifPresent(deviceId -> series.moveLocationSeriesTo(locationSeries, deviceId, scope));

        audit.record(
                AUDIT_PRIMARY_CHANGED,
                Subject.of("location", location.getId()),
                before,
                location.auditState(),
                scope,
                reason);
        events.publish(new LocationPrimaryChanged(
                location.getId(), location.ownerEntityId(), previous, position.getId(), device.orElse(null)));

        return location.getId();
    }
}
