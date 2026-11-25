package lk.coopfed.knoweb.m8reporting.internal.target;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
import lk.coopfed.knoweb.m8reporting.api.CreateShopTarget;
import lk.coopfed.knoweb.m8reporting.api.ShopTargetCreated;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles creation of a shop sales target.
 *
 * Follows the repository command sequence:
 * guards -> mutation -> audit.record(...) -> events.publish(...)
 */
@Service
@CommandHandler(permission = "rpt.target.manage")
public class CreateShopTargetHandler implements Handles<CreateShopTarget, UUID> {

    static final String AUDIT_CREATED = "SHOP_TARGET_CREATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final PartyQueries party;
    private final Clock clock;

    public CreateShopTargetHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, PartyQueries party, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.party = party;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(CreateShopTarget command, ScopeContext scope) {
        // 1. Business guards
        if (!scope.hasActiveScope() || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m8.target.own_required");
        }
        if (command.locationId() == null) {
            throw new ProblemException("m8.target.location_unknown");
        }
        // Location-scoped user may only set targets for their own shop
        if (scope.locationId() != null && !scope.locationId().equals(command.locationId())) {
            throw new ProblemException("m8.target.location_scope_mismatch");
        }
        // Verify shop location belongs to caller's entity and is visible
        Optional<LocationView> location = party.getLocation(command.locationId(), scope);
        if (location.isEmpty()) {
            throw new ProblemException("m8.target.location_unknown");
        }
        if (!scope.entityId().equals(location.get().ownerEntityId())) {
            throw new ProblemException("m8.target.location_scope_mismatch");
        }
        if (command.periodStart() == null
                || command.periodEnd() == null
                || command.periodStart().isAfter(command.periodEnd())) {
            throw new ProblemException("m8.target.period_invalid");
        }
        if (command.targetAmount() == null || command.targetAmount().signum() < 0) {
            throw new ProblemException("m8.target.amount_invalid");
        }

        // Prevent duplicate active targets for the same shop/period
        Integer count = jdbc.queryForObject(
                """
                select count(*)
                  from reporting.shop_target
                 where owner_entity_id = ?
                   and location_id = ?
                   and period_start = ?
                   and period_end = ?
                """,
                Integer.class,
                scope.entityId(),
                command.locationId(),
                Date.valueOf(command.periodStart()),
                Date.valueOf(command.periodEnd()));
        if (count != null && count > 0) {
            throw new ProblemException("m8.target.duplicate");
        }

        // 2. Mutation
        UUID targetId = Ids.next();
        Instant now = clock.instant();
        BigDecimal targetAmount = command.targetAmount().setScale(2, RoundingMode.HALF_UP);
        String currency = (command.currency() == null || command.currency().isBlank()) ? "LKR" : command.currency();

        jdbc.update(
                """
                insert into reporting.shop_target
                       (target_id, owner_entity_id, location_id, period_start, period_end,
                        target_amount, currency, created_at, created_by, updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                targetId,
                scope.entityId(),
                command.locationId(),
                Date.valueOf(command.periodStart()),
                Date.valueOf(command.periodEnd()),
                targetAmount,
                currency,
                Timestamp.from(now),
                scope.userId(),
                Timestamp.from(now),
                scope.userId());

        // 3. Audit
        Map<String, Object> snapshot = Map.of(
                "targetId", targetId.toString(),
                "ownerEntityId", scope.entityId().toString(),
                "locationId", command.locationId().toString(),
                "periodStart", command.periodStart().toString(),
                "periodEnd", command.periodEnd().toString(),
                "targetAmount", targetAmount.toPlainString(),
                "currency", currency);
        audit.record(AUDIT_CREATED, Subject.of("shop_target", targetId), null, snapshot, scope);

        // 4. Events
        events.publish(new ShopTargetCreated(
                targetId,
                scope.entityId(),
                command.locationId(),
                command.periodStart(),
                command.periodEnd(),
                targetAmount,
                currency));

        return targetId;
    }
}
