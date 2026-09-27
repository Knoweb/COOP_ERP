package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceChanged;
import lk.coopfed.knoweb.m5inventory.api.SignOpeningBalance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SignOpeningBalance (doc 25 section 4.7, DRAFT to SIGNED_ENTITY: "entity officer; MFA"). The
 * second factor is the permission's (inv.opening.sign requires it), checked by the kernel.
 *
 * <p>Guards: an OWN user ({@code m5.opening.user_required}: a signature is a person's); the
 * balance visible to the scope ({@code m5.opening.not_found}); DRAFT
 * ({@code m5.opening.not_draft}). Audit {@code OPB_SIGNED_ENTITY}; event
 * {@code opening_balance.changed.v1} with status SIGNED_ENTITY.
 */
@Service
@CommandHandler(permission = "inv.opening.sign", requiresMfa = true)
class SignOpeningBalanceHandler implements Handles<SignOpeningBalance, UUID> {

    static final String AUDIT_SIGNED = "OPB_SIGNED_ENTITY";

    private final OpeningBalanceStore store;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    SignOpeningBalanceHandler(
            OpeningBalanceStore store, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.store = store;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(SignOpeningBalance command, ScopeContext scope) {
        OpeningBalanceGuards.requireUser(scope);
        OpeningBalanceStore.Header balance = store.lock(command.openingBalanceId())
                .orElseThrow(() -> new ProblemException(
                        "m5.opening.not_found",
                        Map.of("openingBalanceId", String.valueOf(command.openingBalanceId()))));
        if (!"DRAFT".equals(balance.status())) {
            throw new ProblemException("m5.opening.not_draft");
        }

        jdbc.update(
                "update inventory.opening_balance set status = 'SIGNED_ENTITY', signed_entity_by = ?, signed_entity_at = ?"
                        + " where opening_balance_id = ?",
                scope.userId(),
                Timestamp.from(clock.instant()),
                balance.openingBalanceId());

        audit.record(
                AUDIT_SIGNED,
                Subject.of("opening_balance", balance.openingBalanceId()),
                Map.of("status", "DRAFT"),
                Map.of("status", "SIGNED_ENTITY", "signedBy", scope.userId()),
                scope);
        events.publish(new OpeningBalanceChanged(
                balance.openingBalanceId(), balance.ownerEntityId(), balance.locationId(), "SIGNED_ENTITY"));
        return balance.openingBalanceId();
    }
}
