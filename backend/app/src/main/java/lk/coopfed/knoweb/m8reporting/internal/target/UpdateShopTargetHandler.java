package lk.coopfed.knoweb.m8reporting.internal.target;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m8reporting.api.ShopTargetUpdated;
import lk.coopfed.knoweb.m8reporting.api.UpdateShopTarget;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles update of an existing shop sales target.
 *
 * Follows the repository command sequence:
 * guards -> mutation -> audit.record(...) -> events.publish(...)
 */
@Service
@CommandHandler(permission = "rpt.target.manage")
public class UpdateShopTargetHandler implements Handles<UpdateShopTarget, Void> {

    static final String AUDIT_UPDATED = "SHOP_TARGET_UPDATED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public UpdateShopTargetHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Void handle(UpdateShopTarget command, ScopeContext scope) {
        // 1. Business guards
        if (!scope.hasActiveScope() || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("m8.target.own_required");
        }
        if (command.targetId() == null) {
            throw new ProblemException("m8.target.not_found");
        }

        Map<String, Object> existing;
        try {
            existing = jdbc.queryForMap(
                    """
                    select target_id, owner_entity_id, location_id, period_start, period_end,
                           target_amount, currency
                      from reporting.shop_target
                     where target_id = ?
                    """,
                    command.targetId());
        } catch (EmptyResultDataAccessException e) {
            throw new ProblemException("m8.target.not_found");
        }

        UUID ownerEntityId = (UUID) existing.get("owner_entity_id");
        UUID locationId = (UUID) existing.get("location_id");
        if (!scope.entityId().equals(ownerEntityId)) {
            throw new ProblemException("m8.target.not_found");
        }
        if (scope.locationId() != null && !scope.locationId().equals(locationId)) {
            throw new ProblemException("m8.target.not_found");
        }
        LocalDate periodStart = command.periodStart() != null
                ? command.periodStart()
                : ((Date) existing.get("period_start")).toLocalDate();
        LocalDate periodEnd =
                command.periodEnd() != null ? command.periodEnd() : ((Date) existing.get("period_end")).toLocalDate();

        if (periodStart.isAfter(periodEnd)) {
            throw new ProblemException("m8.target.period_invalid");
        }
        if (command.targetAmount() == null || command.targetAmount().signum() < 0) {
            throw new ProblemException("m8.target.amount_invalid");
        }

        // Check duplicate if dates were changed
        Integer duplicateCount = jdbc.queryForObject(
                """
                select count(*)
                  from reporting.shop_target
                 where owner_entity_id = ?
                   and location_id = ?
                   and period_start = ?
                   and period_end = ?
                   and target_id <> ?
                """,
                Integer.class,
                scope.entityId(),
                locationId,
                Date.valueOf(periodStart),
                Date.valueOf(periodEnd),
                command.targetId());
        if (duplicateCount != null && duplicateCount > 0) {
            throw new ProblemException("m8.target.duplicate");
        }

        // 2. Mutation
        Instant now = clock.instant();
        BigDecimal targetAmount = command.targetAmount().setScale(2, RoundingMode.HALF_UP);

        jdbc.update(
                """
                update reporting.shop_target
                   set target_amount = ?,
                       period_start = ?,
                       period_end = ?,
                       updated_at = ?,
                       updated_by = ?
                 where target_id = ?
                """,
                targetAmount,
                Date.valueOf(periodStart),
                Date.valueOf(periodEnd),
                Timestamp.from(now),
                scope.userId(),
                command.targetId());

        // 3. Audit
        Map<String, Object> beforeSnapshot = new LinkedHashMap<>();
        beforeSnapshot.put("targetAmount", existing.get("target_amount").toString());
        beforeSnapshot.put("periodStart", existing.get("period_start").toString());
        beforeSnapshot.put("periodEnd", existing.get("period_end").toString());

        Map<String, Object> afterSnapshot = new LinkedHashMap<>();
        afterSnapshot.put("targetAmount", targetAmount.toPlainString());
        afterSnapshot.put("periodStart", periodStart.toString());
        afterSnapshot.put("periodEnd", periodEnd.toString());

        audit.record(
                AUDIT_UPDATED, Subject.of("shop_target", command.targetId()), beforeSnapshot, afterSnapshot, scope);

        // 4. Events
        events.publish(new ShopTargetUpdated(
                command.targetId(), scope.entityId(), locationId, periodStart, periodEnd, targetAmount));

        return null;
    }
}
