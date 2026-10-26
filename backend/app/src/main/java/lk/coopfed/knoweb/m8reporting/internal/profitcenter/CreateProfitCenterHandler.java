package lk.coopfed.knoweb.m8reporting.internal.profitcenter;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m8reporting.api.CreateProfitCenter;
import lk.coopfed.knoweb.m8reporting.api.ProfitCenterCreated;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles creation of a profit center reporting dimension (Polgolla M1-08).
 *
 * Sequence: guards -> mutation -> audit.record(...) -> events.publish(...)
 */
@Service
@CommandHandler(permission = "rpt.profit_center.manage")
public class CreateProfitCenterHandler implements Handles<CreateProfitCenter, UUID> {

    public static final String AUDIT_CREATED = "PROFIT_CENTER_CREATED";
    private static final Set<String> ALLOWED_CATEGORIES = Set.of("AGENCY", "PACKING", "RETAIL", "GENERAL");

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final PartyQueries party;
    private final Clock clock;

    public CreateProfitCenterHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, PartyQueries party, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.party = party;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(CreateProfitCenter command, ScopeContext scope) {
        // 1. Business guards
        if (!scope.hasActiveScope() || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m8.profit_center.own_required");
        }
        if (command.code() == null || command.code().trim().isEmpty()) {
            throw new ProblemException("m8.profit_center.code_required");
        }
        String code = command.code().trim();
        if (code.length() > 32) {
            throw new ProblemException("m8.profit_center.code_too_long");
        }
        if (command.name() == null || command.name().trim().isEmpty()) {
            throw new ProblemException("m8.profit_center.name_required");
        }
        String name = command.name().trim();

        if (command.category() == null
                || !ALLOWED_CATEGORIES.contains(command.category().toUpperCase())) {
            throw new ProblemException("m8.profit_center.category_invalid");
        }
        String category = command.category().toUpperCase();

        if (command.locationId() != null) {
            Optional<LocationView> location = party.getLocation(command.locationId(), scope);
            if (location.isEmpty()) {
                throw new ProblemException("m8.profit_center.location_unknown");
            }
            if (!scope.entityId().equals(location.get().ownerEntityId())) {
                throw new ProblemException("m8.profit_center.location_scope_mismatch");
            }
            if (scope.locationId() != null && !scope.locationId().equals(command.locationId())) {
                throw new ProblemException("m8.profit_center.location_scope_mismatch");
            }
        }

        // Check duplicate code for owner
        Integer existing = jdbc.queryForObject(
                """
                select count(*)
                  from reporting.profit_center
                 where owner_entity_id = ?
                   and lower(code) = lower(?)
                """,
                Integer.class,
                scope.entityId(),
                code);
        if (existing != null && existing > 0) {
            throw new ProblemException("m8.profit_center.duplicate_code");
        }

        // 2. Mutation
        UUID profitCenterId = Ids.next();
        Instant now = clock.instant();
        jdbc.update(
                """
                insert into reporting.profit_center
                       (profit_center_id, owner_entity_id, code, name, category, location_id,
                        status, created_at, created_by, updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """,
                profitCenterId,
                scope.entityId(),
                code,
                name,
                category,
                command.locationId(),
                Timestamp.from(now),
                scope.userId(),
                Timestamp.from(now),
                scope.userId());

        // 3. Audit
        Map<String, Object> snapshot = new java.util.HashMap<>();
        snapshot.put("profitCenterId", profitCenterId.toString());
        snapshot.put("ownerEntityId", scope.entityId().toString());
        snapshot.put("code", code);
        snapshot.put("name", name);
        snapshot.put("category", category);
        if (command.locationId() != null) {
            snapshot.put("locationId", command.locationId().toString());
        }
        audit.record(AUDIT_CREATED, Subject.of("profit_center", profitCenterId), null, snapshot, scope);

        // 4. Domain event
        events.publish(new ProfitCenterCreated(profitCenterId, scope.entityId(), code, category, command.locationId()));

        return profitCenterId;
    }
}
