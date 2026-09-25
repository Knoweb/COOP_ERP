package lk.coopfed.knoweb.m1party.internal.security.role;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.SetSodPair;
import lk.coopfed.knoweb.m1party.api.SodPairChanged;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SetSodPairMode (21A section 6: "both permissions exist; entity scope; upsert sod_pair"). The
 * entity's own row is written; the federation defaults (owner NULL) are never touched. An entity
 * may add a pair or raise one to ROLE mode (doc 19 section 3.2), never lower a pair the
 * Federation has in ROLE mode. Raising to ROLE mode is refused while a role or a person of the
 * entity already holds both halves: the rule would be broken the moment it was made.
 */
@Service
@CommandHandler(permission = "gov.role.manage", requiresMfa = true)
class SetSodPairHandler implements Handles<SetSodPair, UUID> {

    static final String AUDIT = "SOD_PAIR_CHANGED";

    private final RoleGuards guards;
    private final SecurityRecords records;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    SetSodPairHandler(
            RoleGuards guards, SecurityRecords records, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.guards = guards;
        this.records = records;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SetSodPair command, ScopeContext scope) {
        guards.requireEntityWide(scope);
        UUID entityId = scope.entityId();

        String mode = command.mode();
        if (!RoleRules.INSTANCE.equals(mode) && !RoleRules.ROLE.equals(mode)) {
            throw new ProblemException("m1.sod.mode_invalid", Map.of("mode", String.valueOf(mode)));
        }
        if (command.permissionA() == null
                || command.permissionB() == null
                || command.permissionA().equals(command.permissionB())) {
            throw new ProblemException("m1.sod.same_permission");
        }
        List<String> pair = RoleRules.ordered(command.permissionA(), command.permissionB());
        String a = pair.get(0);
        String b = pair.get(1);

        Map<String, SecurityRecords.CatalogueEntry> catalogue = records.catalogue(pair);
        List<String> unknown =
                pair.stream().filter(code -> !catalogue.containsKey(code)).toList();
        if (!unknown.isEmpty()) {
            throw new ProblemException("m1.sod.permission_unknown", Map.of("permissions", String.join(", ", unknown)));
        }

        List<RoleRules.SodRule> inForce = records.pairsFor(entityId);
        Optional<RoleRules.SodRule> federationDefault = inForce.stream()
                .filter(rule -> rule.ownerEntityId() == null
                        && rule.permissionA().equals(a)
                        && rule.permissionB().equals(b))
                .findFirst();
        Optional<RoleRules.SodRule> own = inForce.stream()
                .filter(rule -> entityId.equals(rule.ownerEntityId())
                        && rule.permissionA().equals(a)
                        && rule.permissionB().equals(b))
                .findFirst();

        if (RoleRules.INSTANCE.equals(mode)
                && federationDefault
                        .map(rule -> RoleRules.ROLE.equals(rule.mode()))
                        .orElse(false)) {
            throw new ProblemException("m1.sod.cannot_lower", Map.of("permissionA", a, "permissionB", b));
        }
        String modeNow = RoleRules.modeInForce(a, b, inForce).orElse(null);
        boolean ownAlready = own.map(rule -> rule.mode().equals(mode)).orElse(false);
        if (ownAlready || (own.isEmpty() && mode.equals(modeNow))) {
            throw new ProblemException("m1.sod.unchanged", Map.of("permissionA", a, "permissionB", b, "mode", mode));
        }

        if (RoleRules.ROLE.equals(mode)) {
            List<UUID> roles = records.rolesHoldingBoth(entityId, a, b);
            List<UUID> users = records.usersHoldingBoth(entityId, a, b);
            if (!roles.isEmpty() || !users.isEmpty()) {
                throw new ProblemException(
                        "m1.sod.existing_conflict",
                        Map.of("permissionA", a, "permissionB", b, "roles", roles.size(), "users", users.size()));
            }
        }

        UUID sodPairId;
        Map<String, Object> before = null;
        if (own.isPresent()) {
            sodPairId = own.get().sodPairId();
            before =
                    Map.of("permissionA", a, "permissionB", b, "mode", own.get().mode());
            jdbc.update("update security.sod_pair set mode = ? where sod_pair_id = ?", mode, sodPairId);
        } else {
            sodPairId = Ids.next();
            jdbc.update(
                    "insert into security.sod_pair (sod_pair_id, permission_a, permission_b, mode, owner_entity_id)"
                            + " values (?, ?, ?, ?, ?)",
                    sodPairId,
                    a,
                    b,
                    mode,
                    entityId);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("permissionA", a);
        after.put("permissionB", b);
        after.put("mode", mode);
        audit.record(AUDIT, Subject.of("sod_pair", sodPairId), before, after, scope);
        events.publish(new SodPairChanged(sodPairId, a, b, mode, false));
        return sodPairId;
    }
}
