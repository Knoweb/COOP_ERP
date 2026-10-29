package lk.coopfed.knoweb.m5inventory.internal.reorder;

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
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m5inventory.api.DeleteReorderControl;
import lk.coopfed.knoweb.m5inventory.api.ReorderControlDeleted;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a configured reorder control for an SKU at a location (PGL-M2-01).
 */
@Service
@CommandHandler(permission = "inv.reorder.manage")
public class DeleteReorderControlHandler implements Handles<DeleteReorderControl, Void> {

    public static final String AUDIT_DELETED = "REORDER_CONTROL_DELETED";

    private final PartyQueries party;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    public DeleteReorderControlHandler(
            PartyQueries party, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.party = party;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(DeleteReorderControl command, ScopeContext scope) {
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

        List<UUID> deleted = jdbc.query(
                """
                delete from inventory.reorder_control
                 where owner_entity_id = ? and location_id = ? and sku_id = ?
                returning reorder_control_id
                """,
                (rs, rowNum) -> rs.getObject("reorder_control_id", UUID.class),
                scope.entityId(),
                command.locationId(),
                command.skuId());

        if (deleted.isEmpty()) {
            throw new ProblemException("m5.reorder.not_found");
        }

        UUID reorderControlId = deleted.getFirst();
        audit.record(
                AUDIT_DELETED,
                Subject.of("reorder_control", reorderControlId),
                null,
                Map.of("locationId", command.locationId(), "skuId", command.skuId()),
                scope);

        events.publish(
                new ReorderControlDeleted(reorderControlId, scope.entityId(), command.locationId(), command.skuId()));

        return null;
    }
}
