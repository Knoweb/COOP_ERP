package lk.coopfed.knoweb.m4trading.internal.transfer;

import java.sql.Timestamp;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.RejectTransferRequest;
import lk.coopfed.knoweb.m4trading.api.TransferRequestRejected;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RejectLateralTransfer (24A section 6; doc 24 section 4.7): the society refuses the request.
 *
 * <p>Guards, in order: the society's entity-wide OWN scope; a reason; a request of the caller's
 * entity ({@code m4.transfer.request_not_found}); undecided ({@code m4.transfer.decided}).
 *
 * <p>Mutation: the society's {@code transfer_request_decision} row, REJECTED with the reason.
 * Audit TRANSFER_REJECTED; event transfer_request.rejected.v1.
 */
@Service
@CommandHandler(permission = "mpcs.transfer.approve")
public class RejectTransferRequestHandler implements Handles<RejectTransferRequest, Void> {

    static final String AUDIT_REJECTED = "TRANSFER_REJECTED";

    private final JdbcTemplate jdbc;
    private final TransferRequestReads requests;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RejectTransferRequestHandler(
            JdbcTemplate jdbc,
            TransferRequestReads requests,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RejectTransferRequest command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        String reason = TradingGuards.required(command.reason(), "reason").strip();
        TransferRequestReads.Request request = TransferDecisions.undecided(requests, jdbc, command.requestId(), scope);

        jdbc.update(
                """
                insert into trading.transfer_request_decision (request_id, owner_entity_id, location_id, to_location_id,
                    decision, reason, decided_by, decided_at)
                values (?, ?, ?, ?, 'REJECTED', ?, ?, ?)
                """,
                request.requestId(),
                scope.entityId(),
                request.fromLocationId() != null ? request.fromLocationId() : request.toLocationId(),
                request.toLocationId(),
                reason,
                scope.userId(),
                Timestamp.from(clock.now()));

        audit.record(
                AUDIT_REJECTED,
                Subject.of("transfer_request", request.requestId()),
                Map.of("status", TransferRequestReads.REQUESTED),
                Map.of("status", TransferRequestReads.REJECTED, "reason", reason),
                scope);
        events.publish(new TransferRequestRejected(
                request.requestId(),
                scope.entityId(),
                request.fromLocationId(),
                request.toLocationId(),
                reason,
                scope.userId()));
        return null;
    }
}
