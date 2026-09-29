package lk.coopfed.knoweb.m4trading.internal.transfer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
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
import lk.coopfed.knoweb.m4trading.api.ApproveTransferRequest;
import lk.coopfed.knoweb.m4trading.api.TransferRequestApproved;
import lk.coopfed.knoweb.m4trading.api.TransferRequestLine;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m5inventory.query.Availability;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApproveLateralTransfer (24A section 6; doc 24 section 4.7: "MPCS scope; availability at source
 * (M5)"). The society approves; M5 issues the transfer on transfer_request.approved.v1.
 *
 * <p>Guards, in order: the society's entity-wide OWN scope; a request of the caller's entity
 * ({@code m4.transfer.request_not_found}); undecided (an advisory lock per request; {@code
 * m4.transfer.decided}); a source, the one named here or by the shop ({@code
 * m4.transfer.source_required}), not the shop ({@code m4.transfer.same_location}); both locations
 * the society's ({@code m4.transfer.location_invalid}); every
 * line available at the source now ({@code m4.transfer.insufficient_stock}, M5's availability:
 * GOOD stock not held by a pick list).
 *
 * <p>Mutation: the society's {@code transfer_request_decision} row, APPROVED, at the source. Audit
 * TRANSFER_APPROVED; event transfer_request.approved.v1.
 */
@Service
@CommandHandler(permission = "mpcs.transfer.approve")
public class ApproveTransferRequestHandler implements Handles<ApproveTransferRequest, Void> {

    static final String AUDIT_APPROVED = "TRANSFER_APPROVED";

    private final JdbcTemplate jdbc;
    private final TransferRequestReads requests;
    private final PartyQueries party;
    private final InventoryQueries inventory;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ApproveTransferRequestHandler(
            JdbcTemplate jdbc,
            TransferRequestReads requests,
            PartyQueries party,
            InventoryQueries inventory,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.party = party;
        this.inventory = inventory;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(ApproveTransferRequest command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        TransferRequestReads.Request request = TransferDecisions.undecided(requests, jdbc, command.requestId(), scope);
        UUID from = command.fromLocationId() != null ? command.fromLocationId() : request.fromLocationId();
        if (from == null) {
            throw new ProblemException("m4.transfer.source_required");
        }
        if (from.equals(request.toLocationId())) {
            throw new ProblemException("m4.transfer.same_location");
        }
        for (UUID location : List.of(from, request.toLocationId())) {
            boolean ours = party.getLocation(location, scope)
                    .filter(found -> scope.entityId().equals(found.ownerEntityId()))
                    .isPresent();
            if (!ours) {
                throw new ProblemException("m4.transfer.location_invalid", Map.of("locationId", location));
            }
        }
        Map<UUID, BigDecimal> available = new HashMap<>();
        for (Availability row : inventory.availability(
                List.of(from),
                request.lines().stream().map(TransferRequestLine::skuId).toList(),
                scope)) {
            available.merge(row.skuId(), row.available(), BigDecimal::add);
        }
        for (TransferRequestLine line : request.lines()) {
            if (available.getOrDefault(line.skuId(), BigDecimal.ZERO).compareTo(line.qty()) < 0) {
                throw new ProblemException("m4.transfer.insufficient_stock", Map.of("skuId", line.skuId()));
            }
        }

        jdbc.update(
                """
                insert into trading.transfer_request_decision (request_id, owner_entity_id, location_id, to_location_id,
                    decision, decided_by, decided_at)
                values (?, ?, ?, ?, 'APPROVED', ?, ?)
                """,
                request.requestId(),
                scope.entityId(),
                from,
                request.toLocationId(),
                scope.userId(),
                Timestamp.from(clock.now()));

        audit.record(
                AUDIT_APPROVED,
                Subject.of("transfer_request", request.requestId()),
                Map.of("status", TransferRequestReads.REQUESTED),
                Map.of("status", TransferRequestReads.APPROVED),
                scope);
        events.publish(new TransferRequestApproved(
                request.requestId(), scope.entityId(), from, request.toLocationId(), scope.userId(), request.lines()));
        return null;
    }
}
