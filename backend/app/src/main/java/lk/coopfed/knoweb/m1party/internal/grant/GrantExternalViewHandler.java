package lk.coopfed.knoweb.m1party.internal.grant;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.api.ExternalGrantIssued;
import lk.coopfed.knoweb.m1party.api.GrantExternalView;
import lk.coopfed.knoweb.m1party.internal.entity.FederationCallers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GrantExternalView (21A section 6; doc 21 section 4.6 and flow 6.5). Guards, in order:
 * <ol>
 *   <li>the caller is the Federation, entity-wide ({@code m1.grant.federation_required});</li>
 *   <li>the grantee is a user the Federation can see ({@code m1.grant.grantee_not_found}), of
 *       kind EXTERNAL ({@code m1.grant.grantee_not_external}), not deactivated
 *       ({@code m1.grant.grantee_deactivated});</li>
 *   <li>every entity of the scope exists ({@code m1.grant.entity_not_found});</li>
 *   <li>the window: ends after it starts and no later than the configured maximum after its
 *       start ({@link GrantWindow});</li>
 *   <li>a reason ({@code m1.grant.reason_required}).</li>
 * </ol>
 * Then one ACTIVE row owned by the Federation, the audit record and the event.
 */
@Service
@CommandHandler(permission = "gov.external.grant", requiresMfa = true)
class GrantExternalViewHandler implements Handles<GrantExternalView, UUID> {

    static final String AUDIT_ISSUED = "EXTERNAL_GRANT_ISSUED";

    /** Configuration item (seed/kernel/config-items.yaml); the default is doc 21 DR-4. */
    static final String MAX_MONTHS_SETTING = "m1.external_grant.max_months";

    private static final String EXTERNAL = "EXTERNAL";
    private static final String DEACTIVATED = "DEACTIVATED";

    private final ExternalGrantRows rows;
    private final FederationCallers federation;
    private final ConfigRegistry config;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    GrantExternalViewHandler(
            ExternalGrantRows rows,
            FederationCallers federation,
            ConfigRegistry config,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.rows = rows;
        this.federation = federation;
        this.config = config;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(GrantExternalView command, ScopeContext scope) {

        federation.require(scope, "m1.grant.federation_required");

        requireExternalGrantee(command.granteeUserId());

        List<UUID> entities = scopeEntities(command.scopeEntityIds());

        GrantWindow.Window window = GrantWindow.of(
                command.validFrom(),
                command.validUntil(),
                clock.instant(),
                config.getInt(MAX_MONTHS_SETTING, scope, GrantWindow.MAX_MONTHS),
                rows::plusMonths);

        if (command.reason() == null || command.reason().isBlank()) {
            throw new ProblemException("m1.grant.reason_required");
        }
        String reason = command.reason().strip();

        UUID grantId = Ids.next();

        jdbc.update(
                """
                insert into security.external_grant (
                    grant_id, grantee_user_id, scope_entity_ids, valid_from, valid_until, reason, status,
                    owner_entity_id
                ) values (?, ?, cast(? as uuid[]), ?, ?, ?, 'ACTIVE', ?)
                """,
                grantId,
                command.granteeUserId(),
                ExternalGrantRows.uuidArray(entities),
                ExternalGrantRows.timestamp(window.validFrom()),
                ExternalGrantRows.timestamp(window.validUntil()),
                reason,
                scope.entityId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("granteeUserId", command.granteeUserId());
        after.put("scopeEntityIds", entities);
        after.put("validFrom", window.validFrom());
        after.put("validUntil", window.validUntil());
        after.put("status", ExternalGrantRows.ACTIVE);

        audit.record(AUDIT_ISSUED, Subject.of("external_grant", grantId), null, after, scope, reason);

        events.publish(new ExternalGrantIssued(
                grantId, command.granteeUserId(), entities, window.validFrom(), window.validUntil()));

        return grantId;
    }

    private void requireExternalGrantee(UUID granteeUserId) {
        if (granteeUserId == null) {
            throw new ProblemException("m1.grant.grantee_not_found");
        }
        // Read in the Federation's scope: an external user is created by a Federation
        // administrator and belongs to the Federation (doc 21 flow 6.5).
        Optional<Map<String, Object>> user = rows.user(granteeUserId);
        if (user.isEmpty()) {
            throw new ProblemException("m1.grant.grantee_not_found", Map.of("userId", granteeUserId));
        }
        if (!EXTERNAL.equals(user.get().get("user_kind"))) {
            throw new ProblemException("m1.grant.grantee_not_external", Map.of("userId", granteeUserId));
        }
        if (DEACTIVATED.equals(user.get().get("status"))) {
            throw new ProblemException("m1.grant.grantee_deactivated", Map.of("userId", granteeUserId));
        }
    }

    /** The entities once each, in the order given; every one must exist. */
    private List<UUID> scopeEntities(List<UUID> requested) {
        if (requested == null || requested.isEmpty() || requested.stream().anyMatch(Objects::isNull)) {
            throw new ProblemException("m1.grant.entity_not_found");
        }
        List<UUID> entities = new ArrayList<>(new LinkedHashSet<>(requested));
        List<UUID> missing = rows.missingEntities(entities);
        if (!missing.isEmpty()) {
            throw new ProblemException("m1.grant.entity_not_found", Map.of("entityIds", missing));
        }
        return List.copyOf(entities);
    }
}
