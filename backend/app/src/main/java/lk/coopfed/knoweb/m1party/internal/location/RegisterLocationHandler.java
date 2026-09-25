package lk.coopfed.knoweb.m1party.internal.location;

import java.sql.SQLException;
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
import lk.coopfed.knoweb.m1party.api.LocationRegistered;
import lk.coopfed.knoweb.m1party.api.RegisterLocation;
import lk.coopfed.knoweb.m1party.internal.entity.Entity;
import lk.coopfed.knoweb.m1party.internal.entity.EntityRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 21A section 6, RegisterLocation: owner = caller scope; code unique in the owner; insert
 * PLANNED. Doc 21 section 4.3 adds that the owner is ACTIVE or ONBOARDING. A shop gets its
 * location numbering series here (SeriesHooks), so that naming its primary till later only
 * moves their counters.
 */
@Service
@CommandHandler(permission = "prt.location.register")
class RegisterLocationHandler implements Handles<RegisterLocation, UUID> {

    static final String AUDIT_REGISTERED = "LOCATION_REGISTERED";

    private final LocationRepository locations;
    private final EntityRepository entities;
    private final SeriesHooks series;
    private final AuditFacade audit;
    private final EventPublisher events;

    RegisterLocationHandler(
            LocationRepository locations,
            EntityRepository entities,
            SeriesHooks series,
            AuditFacade audit,
            EventPublisher events) {
        this.locations = locations;
        this.entities = entities;
        this.series = series;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RegisterLocation command, ScopeContext scope) {

        // 1. the owner is the caller's entity, acting entity-wide.
        LocationGuards.requireEntityWideScope(scope);
        Entity owner = entities.findById(scope.entityId())
                .filter(Entity::isTrading)
                .orElseThrow(() -> new ProblemException("m1.location.owner_not_trading"));

        // 2. the code is unique within the owner (the unique key is the backstop for a race).
        String code = command.locationCode().strip();
        if (locations.existsByOwnerEntityIdAndLocationCode(owner.getId(), code)) {
            throw duplicateCode(code);
        }

        // 3. the trading hours make sense (the type is the slice's enum and the table's check).
        String tradingHours = TradingHours.toJson(command.tradingHours());

        // 4. mutation: the location, and for a shop its location series.
        String language = command.language() == null ? owner.defaultLanguage() : command.language();
        Location location = Location.register(
                Ids.next(),
                owner.getId(),
                code,
                command.locationType(),
                new Location.Facts(
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
        try {
            locations.saveAndFlush(location);
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) {
                throw duplicateCode(code);
            }
            throw e;
        }
        series.registerLocationSeries(location, owner.entityCode(), scope);

        // 5. audit, 6. event.
        audit.record(AUDIT_REGISTERED, Subject.of("location", location.getId()), null, location.auditState(), scope);
        events.publish(new LocationRegistered(
                location.getId(),
                location.ownerEntityId(),
                location.locationCode(),
                location.locationType(),
                location.language(),
                location.status()));

        return location.getId();
    }

    private static ProblemException duplicateCode(String code) {
        return new ProblemException("m1.location.code_duplicate", Map.of("locationCode", code));
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
