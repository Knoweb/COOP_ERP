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
import lk.coopfed.knoweb.m5inventory.api.RejectOpeningBalance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RejectOpeningBalance (PGL-M2-06): allows an authorized higher officer (holding inv.opening.sign with MFA) to reject a
 * draft or signed opening balance upon recount discrepancy, recording reason and identity while
 * keeping the historical lines immutable.
 */
@Service
@CommandHandler(permission = "inv.opening.sign", requiresMfa = true)
public class RejectOpeningBalanceHandler implements Handles<RejectOpeningBalance, UUID> {

    public static final String AUDIT_REJECTED = "OPB_REJECTED";

    private final OpeningBalanceStore store;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public RejectOpeningBalanceHandler(
            OpeningBalanceStore store, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, Clock clock) {
        this.store = store;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(RejectOpeningBalance command, ScopeContext scope) {
        OpeningBalanceGuards.requireUser(scope);

        if (command.reason() == null || command.reason().trim().length() < 5) {
            throw new ProblemException("m5.opening.rejection_reason_required");
        }

        OpeningBalanceStore.Header balance = store.lock(command.openingBalanceId())
                .orElseThrow(() -> new ProblemException(
                        "m5.opening.not_found",
                        Map.of("openingBalanceId", String.valueOf(command.openingBalanceId()))));

        if ("POSTED".equals(balance.status())) {
            throw new ProblemException("m5.opening.cannot_reject_posted");
        }
        if ("REJECTED".equals(balance.status())) {
            throw new ProblemException("m5.opening.already_rejected");
        }

        String oldStatus = balance.status();
        String reason = command.reason().trim();

        jdbc.update(
                """
                update inventory.opening_balance
                   set status = 'REJECTED',
                       rejected_by = ?,
                       rejected_at = ?,
                       rejection_reason = ?
                 where opening_balance_id = ?
                """,
                scope.userId(),
                Timestamp.from(clock.instant()),
                reason,
                balance.openingBalanceId());

        audit.record(
                AUDIT_REJECTED,
                Subject.of("opening_balance", balance.openingBalanceId()),
                Map.of("status", oldStatus),
                Map.of("status", "REJECTED", "reason", reason, "rejectedBy", scope.userId()),
                scope);

        events.publish(new OpeningBalanceChanged(
                balance.openingBalanceId(), balance.ownerEntityId(), balance.locationId(), "REJECTED"));

        return balance.openingBalanceId();
    }
}
