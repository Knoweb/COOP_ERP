package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.IssueTransfer;
import lk.coopfed.knoweb.m5inventory.api.TransferRequestUnfilled;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An approved transfer request of which nothing could be sent is flagged, not dead-lettered
 * (wave 3, M1M2M3M5-17; completes wave 2's M5-03). Before, {@link TransferRequestConsumer} threw
 * {@code m5.transfer.insufficient_stock}: the event was retried, then sent to the dead letter
 * queue, and nobody was told. Now the request is recorded as {@code TRANSFER_REQUEST_SHORT}
 * (REVIEW), the same flag a request sent short gets, with every item at zero sent, and the shop
 * raises a new request when the stock is there.
 *
 * <p>Guards: an OWN scope ({@code m5.scope.own_required}; the consumer is delivered in the
 * society's); the request named ({@code request.invalid}); at least one item
 * ({@code m5.transfer.lines_required}). Mutation: none. Audit {@code TRANSFER_REQUEST_SHORT};
 * event {@code transfer_request.unfilled.v1}. Permission: that of the issue it stands in for.
 */
@Service
@CommandHandler(permission = "inv.transfer.issue")
class RecordTransferRequestUnfilledHandler implements Handles<RecordTransferRequestUnfilled, Void> {

    /** The flag IssueTransferHandler writes for a request sent short (wave 2, M5-03). */
    static final String AUDIT_REQUEST_SHORT = "TRANSFER_REQUEST_SHORT";

    private final AuditFacade audit;
    private final EventPublisher events;

    RecordTransferRequestUnfilledHandler(AuditFacade audit, EventPublisher events) {
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(RecordTransferRequestUnfilled command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        if (command == null || command.transferRequestId() == null) {
            throw new ProblemException("request.invalid");
        }
        if (command.shortfalls() == null || command.shortfalls().isEmpty()) {
            throw new ProblemException("m5.transfer.lines_required");
        }

        List<Map<String, Object>> shortLines = new ArrayList<>();
        for (IssueTransfer.Shortfall shortfall : command.shortfalls()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("skuId", shortfall.skuId());
            entry.put("wanted", shortfall.wanted().toPlainString());
            entry.put("sent", shortfall.sent().toPlainString());
            shortLines.add(entry);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("transferRequestId", String.valueOf(command.transferRequestId()));
        after.put("fromLocationId", command.fromLocationId());
        after.put("toLocationId", command.toLocationId());
        after.put("lines", shortLines);
        audit.record(
                AUDIT_REQUEST_SHORT,
                Subject.of("transfer_request", command.transferRequestId()),
                null,
                after,
                scope,
                "The approved request could not be filled at all; no transfer was issued and the shop raises a"
                        + " new request when the stock is there");
        events.publish(new TransferRequestUnfilled(
                command.transferRequestId(),
                scope.entityId(),
                command.fromLocationId(),
                command.toLocationId(),
                command.shortfalls().size()));
        return null;
    }
}
