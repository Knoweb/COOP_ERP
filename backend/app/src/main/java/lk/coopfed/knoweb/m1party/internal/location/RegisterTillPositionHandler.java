package lk.coopfed.knoweb.m1party.internal.location;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.RegisterTillPosition;
import lk.coopfed.knoweb.m1party.api.TillPositionRegistered;
import lk.coopfed.knoweb.m1party.internal.entity.Entity;
import lk.coopfed.knoweb.m1party.internal.entity.EntityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, RegisterTillPosition: the location is a SHOP; the position number is unique
 * there (retired positions keep theirs); insert; NumberingService.registerSeries(TILL_POSITION)
 * for every type numbered per till. This is M1-05's "done when": the series exist the moment the
 * position does, in the same transaction.
 */
@Service
@CommandHandler(permission = "prt.position.manage")
class RegisterTillPositionHandler implements Handles<RegisterTillPosition, UUID> {

    static final String AUDIT_REGISTERED = "POSITION_REGISTERED";

    private final LocationRepository locations;
    private final TillPositionRepository positions;
    private final EntityRepository entities;
    private final SeriesHooks series;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterTillPositionHandler(
            LocationRepository locations,
            TillPositionRepository positions,
            EntityRepository entities,
            SeriesHooks series,
            AuditFacade audit,
            EventPublisher events) {
        this.locations = locations;
        this.positions = positions;
        this.entities = entities;
        this.series = series;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterTillPosition command, ScopeContext scope) {

        // 1. the location is a SHOP.
        LocationGuards.requireOwnScope(scope);
        Location location = LocationGuards.location(locations, command.locationId());
        if (!location.isShop()) {
            throw new ProblemException(
                    "m1.position.location_not_shop",
                    Map.of("locationId", location.getId(), "locationType", location.locationType()));
        }
        // 2. the number is unused at the shop.
        if (positions.existsByLocationIdAndPositionNo(location.getId(), (short) command.positionNo())) {
            throw duplicateNumber(command.positionNo());
        }

        // Mutation: the position, then its series.
        TillPosition position = TillPosition.register(Ids.next(), location, command.positionNo());
        try {
            positions.saveAndFlush(position);
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) {
                throw duplicateNumber(command.positionNo());
            }
            throw e;
        }
        String entityCode = entities.findById(location.ownerEntityId())
                .map(Entity::entityCode)
                .orElseThrow(() -> new IllegalStateException("The owner of location " + location.getId()
                        + " is not visible in the scope that sees the location"));
        List<UUID> seriesIds = series.registerPositionSeries(location, position, entityCode, scope);

        audit.record(
                AUDIT_REGISTERED, Subject.of("till_position", position.getId()), null, position.auditState(), scope);
        events.publish(new TillPositionRegistered(
                position.getId(), location.getId(), location.ownerEntityId(), position.positionNo(), seriesIds));

        return position.getId();
    }

    private static ProblemException duplicateNumber(int positionNo) {
        return new ProblemException("m1.position.number_duplicate", Map.of("positionNo", positionNo));
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
