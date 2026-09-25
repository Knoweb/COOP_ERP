package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.RemoveSodPair;
import lk.coopfed.knoweb.m1party.api.SodPairChanged;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes a pair the entity added itself; what the Federation set by default stays in force
 * (an entity may make the rules stricter, never looser). The row is deleted and the audit
 * record keeps it.
 */
@Service
@CommandHandler(permission = "gov.role.manage", requiresMfa = true)
class RemoveSodPairHandler implements Handles<RemoveSodPair, UUID> {

    static final String AUDIT = "SOD_PAIR_CHANGED";

    private final RoleGuards guards;
    private final SecurityRecords records;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    RemoveSodPairHandler(
            RoleGuards guards, SecurityRecords records, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.guards = guards;
        this.records = records;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RemoveSodPair command, ScopeContext scope) {
        guards.requireEntityWide(scope);

        RoleRules.SodRule pair = records.pair(command.sodPairId())
                .orElseThrow(() -> new ProblemException(
                        "m1.sod.not_found", Map.of("sodPairId", String.valueOf(command.sodPairId()))));
        if (!scope.entityId().equals(pair.ownerEntityId())) {
            throw new ProblemException("m1.sod.not_owner", Map.of("sodPairId", pair.sodPairId()));
        }

        jdbc.update("delete from security.sod_pair where sod_pair_id = ?", pair.sodPairId());

        audit.record(
                AUDIT,
                Subject.of("sod_pair", pair.sodPairId()),
                Map.of("permissionA", pair.permissionA(), "permissionB", pair.permissionB(), "mode", pair.mode()),
                null,
                scope);
        events.publish(new SodPairChanged(pair.sodPairId(), pair.permissionA(), pair.permissionB(), pair.mode(), true));
        return pair.sodPairId();
    }
}
