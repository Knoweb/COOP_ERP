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
import lk.coopfed.knoweb.kernel.api.SyncStatus;
import lk.coopfed.knoweb.m1party.api.LocationPrimaryChanged;
import lk.coopfed.knoweb.m1party.api.SetPrimaryTill;
import lk.coopfed.knoweb.m1party.internal.entity.Entity;
import lk.coopfed.knoweb.m1party.internal.entity.EntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, SetPrimaryTill: the position belongs to the location and is ACTIVE; a reason.
 * The primary till holds the shop's location series (doc 21 A-I7: a shop issues location-series
 * documents only once it has one): when an ACTIVE device is at the new position, the counters
 * move to it through the kernel (NumberingService.holderChange, which publishes
 * series.holder_changed.v1). With no active device there yet, the counters stay where they are;
 * M1-06's AssignDeviceToPosition moves them when the primary till gets its device (21A section
 * 6.1). A SUSPENDED device at the new position (it keeps its position until a replacement
 * comes, M1-06) never receives the counters: nothing could number a GRN offline then.
 *
 * <p>Before the counters move, the device that holds them, the one at the old primary till,
 * must have sent everything it issued, or the administrator records the loss, the same rule as
 * AssignDeviceToPosition makes before any counter moves (21A section 6, "old device outbox
 * drained (kernel SyncStatus.drained) or loss recorded"). Otherwise a GRN the old till numbered
 * offline and never uploaded would be numbered again by the new one.
 */
@Service
@CommandHandler(permission = "prt.location.primary", requiresMfa = true)
class SetPrimaryTillHandler implements Handles<SetPrimaryTill, UUID> {

    static final String AUDIT_PRIMARY_CHANGED = "LOCATION_PRIMARY_CHANGED";

    /** The same audit type as AssignDeviceToPosition's: one record of a documented gap. */
    static final String AUDIT_OUTBOX_LOSS = "DEVICE_OUTBOX_LOSS_RECORDED";

    private final LocationRepository locations;
    private final TillPositionRepository positions;
    private final EntityRepository entities;
    private final LocationFacts facts;
    private final SyncStatus sync;
    private final SeriesHooks series;
    private final AuditFacade audit;
    private final EventPublisher events;

    SetPrimaryTillHandler(
            LocationRepository locations,
            TillPositionRepository positions,
            EntityRepository entities,
            LocationFacts facts,
            SyncStatus sync,
            SeriesHooks series,
            AuditFacade audit,
            EventPublisher events) {
        this.locations = locations;
        this.positions = positions;
        this.entities = entities;
        this.facts = facts;
        this.sync = sync;
        this.series = series;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SetPrimaryTill command, ScopeContext scope) {

        // 1. the position belongs to the location. The location row is locked first, then the
        //    position (the order every location handler keeps), so that RetireTillPosition
        //    cannot retire this position while it becomes the primary till.
        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.lockedLocation(locations, command.locationId());
        TillPosition position = LocationGuards.lockedPosition(positions, command.tillPositionId());
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
        // 4. the counters move only to an ACTIVE device, and only away from a drained one.
        UUID previous = location.primaryTillPositionId();
        Optional<UUID> newDevice = facts.holderAt(position.getId())
                .filter(LocationFacts.DeviceAt::isActive)
                .map(LocationFacts.DeviceAt::deviceId);
        Optional<UUID> oldDevice = previous == null ? Optional.empty() : facts.deviceAt(previous);
        boolean countersMove = newDevice.isPresent() && oldDevice.isPresent() && !oldDevice.equals(newDevice);
        boolean lossRecorded = false;
        if (countersMove && !sync.drained(oldDevice.get())) {
            if (!command.outboxLossRecorded()) {
                throw new ProblemException(
                        "m1.device.outbox_not_drained", Map.of("deviceIds", List.of(oldDevice.get())));
            }
            lossRecorded = true;
        }

        // Mutation: the flag, then the counters of the location series.
        Map<String, Object> before = location.auditState();
        location.namePrimaryTill(position.getId());
        locations.saveAndFlush(location);

        String entityCode = entities.findById(location.ownerEntityId())
                .map(Entity::entityCode)
                .orElseThrow(() -> new IllegalStateException("The owner of location " + location.getId()
                        + " is not visible in the scope that sees the location"));
        List<UUID> locationSeries = series.registerLocationSeries(location, entityCode, scope);
        newDevice.ifPresent(deviceId -> series.moveLocationSeriesTo(locationSeries, deviceId, scope));

        audit.record(
                AUDIT_PRIMARY_CHANGED,
                Subject.of("location", location.getId()),
                before,
                location.auditState(),
                scope,
                reason);
        if (lossRecorded) {
            // The numbers the old till took from the location series and never sent become a
            // documented gap (doc 32 section 8), recorded against the position it held them for.
            audit.record(
                    AUDIT_OUTBOX_LOSS,
                    Subject.of("till_position", previous),
                    null,
                    Map.of("deviceIds", List.of(oldDevice.get()), "seriesIds", List.copyOf(locationSeries)),
                    scope,
                    reason);
        }
        events.publish(new LocationPrimaryChanged(
                location.getId(), location.ownerEntityId(), previous, position.getId(), newDevice.orElse(null)));

        return location.getId();
    }
}
