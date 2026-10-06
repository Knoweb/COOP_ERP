package lk.coopfed.knoweb.m4trading.internal.delivery;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.DeliveryLineSummary;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteIssued;
import lk.coopfed.knoweb.m4trading.api.DropSummary;
import lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote;
import lk.coopfed.knoweb.m4trading.internal.delivery.AllocatedLines.AllocatedLine;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.order.OrderLocks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IssueDeliveryNote (24A section 6). Guards: the seller's entity-wide OWN scope; its own DRAFT
 * note; under the per-order lock of every order on the note ({@link OrderLocks}, sorted), each
 * order still accepted and not cancelled; per order line, re-checked under a lock of the seller's allocation line, the note's
 * quantity no more than what is allocated and not yet dispatched (another note may have been
 * issued since this one was drafted). Mutation: the seller's ENTITY series of DN, the issuance,
 * the allocation lines' fulfilled quantity raised by what the note dispatches (the order's
 * PARTIALLY_FULFILLED or FULFILLED is derived from it). Audit DN_ISSUED; event
 * delivery_note.issued.v1. The change-log to each drop's shop (the expected-drop snapshot) is
 * deferred with the snapshot contributor.
 */
@Service
@CommandHandler(permission = "del.note.issue")
public class IssueDeliveryNoteHandler implements Handles<IssueDeliveryNote, String> {

    static final String AUDIT_ISSUED = "DN_ISSUED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final TradingSeries series;
    private final AllocatedLines allocated;
    private final DeliveryReads reads;
    private final AuditFacade audit;
    private final EventPublisher events;

    IssueDeliveryNoteHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            TradingSeries series,
            AllocatedLines allocated,
            DeliveryReads reads,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.series = series;
        this.allocated = allocated;
        this.reads = reads;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public String handle(IssueDeliveryNote command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID noteId = TradingGuards.required(command.deliveryNoteId(), "deliveryNoteId");
        DocumentRecord note = DeliveryGuards.ownNote(documents, noteId, scope);
        if (!TradingDocuments.DRAFT.equals(note.status())) {
            throw new ProblemException("m4.delivery.not_draft");
        }
        List<DropSummary> drops = reads.drops(noteId);
        // The buyer's CancelOrder takes the same per-order lock: once held, a cancelled order is
        // seen as cancelled here and the cancel sees what this note dispatched (wave 2,
        // M4MONEY-07). Several orders are locked in sorted order, so two notes never deadlock.
        OrderLocks.lockAll(jdbc, reads.allOrderIds(drops));
        Map<UUID, BigDecimal> dispatched = new HashMap<>();
        for (DropSummary drop : drops) {
            for (DeliveryLineSummary line : drop.lines()) {
                dispatched.merge(line.orderLineId(), line.dispatchedQty(), BigDecimal::add);
            }
        }
        for (Map.Entry<UUID, BigDecimal> entry : dispatched.entrySet()) {
            AllocatedLine source = allocated.find(entry.getKey(), scope.entityId(), true);
            if (entry.getValue().compareTo(source.undispatched()) > 0) {
                throw new ProblemException(
                        "m4.delivery.exceeds_allocation",
                        Map.of("orderLineId", entry.getKey(), "undispatched", source.undispatched()));
            }
        }

        series.ensureEntitySeries(DeliveryReads.DN, scope);
        DocumentRecord issued = issuance.issue(note, documents.findLines(noteId), scope);
        for (Map.Entry<UUID, BigDecimal> entry : dispatched.entrySet()) {
            jdbc.update(
                    "update trading.order_allocation_line set fulfilled_qty = fulfilled_qty + ? where order_line_id = ?",
                    entry.getValue(),
                    entry.getKey());
        }

        List<UUID> orderIds = reads.orderIds(drops);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", TradingDocuments.ISSUED);
        after.put("docNumber", issued.docNumberDisplay());
        after.put("orderIds", orderIds);
        audit.record(
                AUDIT_ISSUED,
                Subject.of("delivery_note", noteId),
                Map.of("status", TradingDocuments.DRAFT),
                after,
                scope);

        events.publish(new DeliveryNoteIssued(
                noteId,
                issued.docNumberDisplay(),
                scope.entityId(),
                note.counterpartyEntityId(),
                orderIds,
                drops,
                note.locationId()));
        return issued.docNumberDisplay();
    }
}
