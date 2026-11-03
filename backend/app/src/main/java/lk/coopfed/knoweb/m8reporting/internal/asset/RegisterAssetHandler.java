package lk.coopfed.knoweb.m8reporting.internal.asset;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
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
import lk.coopfed.knoweb.m8reporting.api.AssetRegistered;
import lk.coopfed.knoweb.m8reporting.api.RegisterAsset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles registration of a society-owned fixed asset (Polgolla M1-04).
 *
 * Sequence: guards -> mutation -> audit.record(...) -> events.publish(...)
 */
@Service
@CommandHandler(permission = "rpt.asset.manage")
public class RegisterAssetHandler implements Handles<RegisterAsset, UUID> {

    public static final String AUDIT_REGISTERED = "ASSET_REGISTERED";
    private static final Set<String> ALLOWED_TYPES =
            Set.of("LAND", "BUILDING", "VEHICLE", "EQUIPMENT", "FURNITURE", "OTHER");

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final PartyQueries party;
    private final Clock clock;

    public RegisterAssetHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, PartyQueries party, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.party = party;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(RegisterAsset command, ScopeContext scope) {
        // 1. Business guards
        if (!scope.hasActiveScope() || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m8.asset.own_required");
        }
        if (command.assetCode() == null || command.assetCode().trim().isEmpty()) {
            throw new ProblemException("m8.asset.code_required");
        }
        String assetCode = command.assetCode().trim();
        if (assetCode.length() > 32) {
            throw new ProblemException("m8.asset.code_too_long");
        }
        if (command.name() == null || command.name().trim().isEmpty()) {
            throw new ProblemException("m8.asset.name_required");
        }
        String name = command.name().trim();

        if (command.assetType() == null
                || !ALLOWED_TYPES.contains(command.assetType().toUpperCase())) {
            throw new ProblemException("m8.asset.type_invalid");
        }
        String assetType = command.assetType().toUpperCase();

        if (scope.locationId() != null) {
            // Location-scoped callers must provide a location matching their active scope
            if (command.locationId() == null || !scope.locationId().equals(command.locationId())) {
                throw new ProblemException("m8.asset.location_scope_mismatch");
            }
        }

        if (command.locationId() != null) {
            Optional<LocationView> location = party.getLocation(command.locationId(), scope);
            if (location.isEmpty()) {
                throw new ProblemException("m8.asset.location_unknown");
            }
            if (!scope.entityId().equals(location.get().ownerEntityId())) {
                throw new ProblemException("m8.asset.location_scope_mismatch");
            }
        }

        // Check duplicate code for owner
        Integer existing = jdbc.queryForObject(
                """
                select count(*)
                  from reporting.asset
                 where owner_entity_id = ?
                   and lower(asset_code) = lower(?)
                """,
                Integer.class,
                scope.entityId(),
                assetCode);
        if (existing != null && existing > 0) {
            throw new ProblemException("m8.asset.duplicate_code");
        }

        // 2. Mutation
        UUID assetId = Ids.next();
        Instant now = clock.instant();
        Date acquisitionDateSql = command.acquisitionDate() != null ? Date.valueOf(command.acquisitionDate()) : null;

        jdbc.update(
                """
                insert into reporting.asset
                       (asset_id, owner_entity_id, asset_code, name, description, asset_type, location_id,
                        acquisition_date, acquisition_cost, status, created_at, created_by, updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """,
                assetId,
                scope.entityId(),
                assetCode,
                name,
                command.description(),
                assetType,
                command.locationId(),
                acquisitionDateSql,
                command.acquisitionCost(),
                Timestamp.from(now),
                scope.userId(),
                Timestamp.from(now),
                scope.userId());

        // 3. Audit
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("assetId", assetId.toString());
        snapshot.put("ownerEntityId", scope.entityId().toString());
        snapshot.put("assetCode", assetCode);
        snapshot.put("name", name);
        snapshot.put("assetType", assetType);
        if (command.locationId() != null) {
            snapshot.put("locationId", command.locationId().toString());
        }
        if (command.acquisitionDate() != null) {
            snapshot.put("acquisitionDate", command.acquisitionDate().toString());
        }
        if (command.acquisitionCost() != null) {
            snapshot.put("acquisitionCost", command.acquisitionCost().toString());
        }
        audit.record(AUDIT_REGISTERED, Subject.of("asset", assetId), null, snapshot, scope);

        // 4. Domain event (sensitive fields omitted)
        events.publish(new AssetRegistered(assetId, scope.entityId(), assetCode, assetType, command.locationId()));

        return assetId;
    }
}
