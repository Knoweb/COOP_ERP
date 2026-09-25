package lk.coopfed.knoweb.m1party.internal.location;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.RetireTillPosition;
import lk.coopfed.knoweb.m1party.api.TillPositionRetired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, RetireTillPosition: no device assigned; status RETIRED; closeSeries. A closed
 * series issues nothing and is never reopened (19A section 7), so a retired position stays
 * retired. Two things beyond the specification: the primary till of its shop cannot retire (the
 * location series would be left with a holder at a position that no longer exists; name another
 * primary till first), and "no open session" waits for M6's query (said in PROGRESS.md).
 */
@Service
@CommandHandler(permission = "prt.position.manage")
class RetireTillPositionHandler implements Handles<RetireTillPosition, UUID> {

    static final String AUDIT_RETIRED = "POSITION_RETIRED";

    private final LocationRepository locations;
    private final TillPositionRepository positions;
    private final LocationFacts facts;
    private final SeriesHooks series;
    private final AuditFacade audit;
    private final EventPublisher events;

    RetireTillPositionHandler(
            LocationRepository locations,
            TillPositionRepository positions,
            LocationFacts facts,
            SeriesHooks series,
            AuditFacade audit,
            EventPublisher events) {
        this.locations = locations;
        this.positions = positions;
        this.facts = facts;
        this.series = series;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RetireTillPosition command, ScopeContext scope) {

        LocationGuards.requireOwnScope(scope);
        TillPosition position = LocationGuards.position(positions, command.tillPositionId());
        if (!position.isActive()) {
            throw new ProblemException("m1.position.not_active", Map.of("tillPositionId", position.getId()));
        }
        // 1. no device assigned.
        if (facts.deviceAt(position.getId()).isPresent()) {
            throw new ProblemException("m1.position.device_assigned", Map.of("tillPositionId", position.getId()));
        }
        Location location = LocationGuards.location(locations, position.locationId());
        if (position.getId().equals(location.primaryTillPositionId())) {
            throw new ProblemException("m1.position.is_primary", Map.of("tillPositionId", position.getId()));
        }

        Map<String, Object> before = position.auditState();
        position.retire();
        positions.saveAndFlush(position);
        List<UUID> closed = series.closePositionSeries(position, scope);

        audit.record(
                AUDIT_RETIRED, Subject.of("till_position", position.getId()), before, position.auditState(), scope);
        events.publish(new TillPositionRetired(
                position.getId(), position.locationId(), position.ownerEntityId(), position.positionNo(), closed));

        return position.getId();
    }
}
