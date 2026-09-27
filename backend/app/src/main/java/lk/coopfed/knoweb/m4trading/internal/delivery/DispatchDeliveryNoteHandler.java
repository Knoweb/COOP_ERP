package lk.coopfed.knoweb.m4trading.internal.delivery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteDispatched;
import lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatch (24A section 6). Guards: the seller's entity-wide OWN scope; its own ISSUED note; a
 * vehicle and a driver (named now or on the draft). Mutation: vehicle, driver and dispatch time on
 * {@code doc_delivery}; ISSUED to IN_TRANSIT. Audit DN_DISPATCHED; event
 * delivery_note.dispatched.v1 (no person's name in it).
 */
@Service
@CommandHandler(permission = "del.note.dispatch")
public class DispatchDeliveryNoteHandler implements Handles<DispatchDeliveryNote, Void> {

    static final String AUDIT_DISPATCHED = "DN_DISPATCHED";
    static final String IN_TRANSIT = "IN_TRANSIT";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    DispatchDeliveryNoteHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Void handle(DispatchDeliveryNote command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID noteId = TradingGuards.required(command.deliveryNoteId(), "deliveryNoteId");
        DocumentRecord note = DeliveryGuards.ownNote(documents, noteId, scope);
        if (!TradingDocuments.ISSUED.equals(note.status())) {
            throw new ProblemException("m4.delivery.not_issued", Map.of("status", note.status()));
        }
        Map<String, Object> current = jdbc.queryForMap(
                "select vehicle_ref, driver_user_id, driver_name from trading.doc_delivery where document_id = ?", noteId);
        String vehicle = blankToNull(command.vehicleRef()) != null
                ? command.vehicleRef().strip()
                : (String) current.get("vehicle_ref");
        UUID driverUser = command.driverUserId() != null ? command.driverUserId() : (UUID) current.get("driver_user_id");
        String driverName = blankToNull(command.driverName()) != null
                ? command.driverName().strip()
                : (String) current.get("driver_name");
        if (vehicle == null) {
            throw new ProblemException("m4.delivery.vehicle_required");
        }
        if (driverUser == null && driverName == null) {
            throw new ProblemException("m4.delivery.driver_required");
        }

        Instant now = clock.now();
        jdbc.update(
                """
                update trading.doc_delivery set vehicle_ref = ?, driver_user_id = ?, driver_name = ?, dispatched_at = ?
                 where document_id = ?
                """,
                vehicle,
                driverUser,
                driverName,
                Timestamp.from(now),
                noteId);
        documents.addStateTransition(
                clock.transition(noteId, TradingDocuments.ISSUED, IN_TRANSIT, scope.userId(), null, null), scope);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", IN_TRANSIT);
        after.put("vehicleRef", vehicle);
        after.put("dispatchedAt", now);
        audit.record(
                AUDIT_DISPATCHED,
                Subject.of("delivery_note", noteId),
                Map.of("status", TradingDocuments.ISSUED),
                after,
                scope);

        events.publish(new DeliveryNoteDispatched(
                noteId,
                note.docNumberDisplay(),
                scope.entityId(),
                note.counterpartyEntityId(),
                now,
                vehicle,
                driverUser));
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
