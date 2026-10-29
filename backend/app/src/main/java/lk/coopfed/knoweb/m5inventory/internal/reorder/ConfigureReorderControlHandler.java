package lk.coopfed.knoweb.m5inventory.internal.reorder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m5inventory.api.ConfigureReorderControl;
import lk.coopfed.knoweb.m5inventory.api.ReorderControlConfigured;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configure or update reorder thresholds for an SKU at a location (PGL-M2-01).
 *
 * <p>Guards:
 * <ul>
 *   <li>Scope must be OWN</li>
 *   <li>Location must exist and belong to the caller entity</li>
 *   <li>SKU must exist in the catalogue</li>
 *   <li>Reorder level must be >= 0 with at most 3 decimal places</li>
 *   <li>Reorder quantity must be > 0 with at most 3 decimal places</li>
 *   <li>Max quantity, if provided, must be >= reorder level with at most 3 decimal places</li>
 * </ul>
 */
@Service
@CommandHandler(permission = "inv.reorder.manage")
public class ConfigureReorderControlHandler implements Handles<ConfigureReorderControl, UUID> {

    public static final String AUDIT_CONFIGURED = "REORDER_CONTROL_CONFIGURED";

    private final PartyQueries party;
    private final CatalogueQueries catalogue;
    private final LowStockEvaluationHandler evaluationHandler;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    public ConfigureReorderControlHandler(
            PartyQueries party,
            CatalogueQueries catalogue,
            LowStockEvaluationHandler evaluationHandler,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.party = party;
        this.catalogue = catalogue;
        this.evaluationHandler = evaluationHandler;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ConfigureReorderControl command, ScopeContext scope) {
        // ---- guards ------------------------------------------------------------------------
        ControlPolicy.requireOwn(scope);
        if (command.locationId() == null) {
            throw new ProblemException("m5.reorder.location_required");
        }
        if (command.skuId() == null) {
            throw new ProblemException("m5.reorder.sku_not_found");
        }
        if (party.getLocation(command.locationId(), scope)
                .filter(l -> scope.entityId().equals(l.ownerEntityId()))
                .isEmpty()) {
            throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", command.locationId()));
        }
        if (scope.locationId() != null && !scope.locationId().equals(command.locationId())) {
            throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", command.locationId()));
        }
        if (catalogue.getSku(command.skuId(), scope).isEmpty()) {
            throw new ProblemException("m5.reorder.sku_not_found", Map.of("skuId", command.skuId()));
        }

        BigDecimal reorderLevel = command.reorderLevel();
        if (reorderLevel == null || reorderLevel.signum() < 0 || reorderLevel.scale() > 3) {
            throw new ProblemException("m5.reorder.invalid_level");
        }

        BigDecimal reorderQty = command.reorderQty();
        if (reorderQty == null || reorderQty.signum() <= 0 || reorderQty.scale() > 3) {
            throw new ProblemException("m5.reorder.invalid_qty");
        }

        BigDecimal maxQty = command.maxQty();
        if (maxQty != null && (maxQty.signum() <= 0 || maxQty.compareTo(reorderLevel) < 0 || maxQty.scale() > 3)) {
            throw new ProblemException("m5.reorder.invalid_max");
        }

        // ---- mutation ----------------------------------------------------------------------
        Instant now = clock.instant();
        UUID newId = Ids.next();
        UUID reorderControlId = jdbc.queryForObject(
                """
                insert into inventory.reorder_control
                    (reorder_control_id, owner_entity_id, location_id, sku_id,
                     reorder_level, reorder_qty, max_qty, alert_triggered, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, false, ?, ?)
                on conflict (owner_entity_id, location_id, sku_id)
                do update set
                    reorder_level = excluded.reorder_level,
                    reorder_qty = excluded.reorder_qty,
                    max_qty = excluded.max_qty,
                    alert_triggered = false,
                    updated_at = excluded.updated_at
                returning reorder_control_id
                """,
                UUID.class,
                newId,
                scope.entityId(),
                command.locationId(),
                command.skuId(),
                reorderLevel,
                reorderQty,
                maxQty,
                Timestamp.from(now),
                Timestamp.from(now));

        // ---- audit -------------------------------------------------------------------------
        audit.record(
                AUDIT_CONFIGURED,
                Subject.of("reorder_control", reorderControlId),
                null,
                Map.of(
                        "locationId",
                        command.locationId(),
                        "skuId",
                        command.skuId(),
                        "reorderLevel",
                        reorderLevel,
                        "reorderQty",
                        reorderQty),
                scope);

        // ---- events ------------------------------------------------------------------------
        events.publish(new ReorderControlConfigured(
                reorderControlId,
                scope.entityId(),
                command.locationId(),
                command.skuId(),
                reorderLevel,
                reorderQty,
                maxQty));

        // ---- immediate evaluation ----------------------------------------------------------
        evaluationHandler.evaluateSku(command.locationId(), command.skuId(), scope);

        return reorderControlId;
    }
}
