package lk.coopfed.knoweb.m6pos.internal.ingest;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m6pos.api.ReceiptRecorded;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RecordReceipt: the minimal ReceiptBundleHook of 26A section 10. The receipt a till issued
 * offline from its own series is kept as issued, with the number, device sequence and content hash
 * it carries, its lines and its tenders, at the device's shop. M5 deducts the stock from the same
 * bundle ({@code m5.sales}); M7's account postings and the re-validation against the snapshot
 * are later work (README).
 *
 * <p><b>A fact is never refused for a business reason</b> (AGENTS.md): what central finds odd is
 * a flag on the receipt and a REVIEW audit record ({@code RECEIPT_FLAGGED}), and the receipt is
 * kept. Flags: {@code LOCATION_MISMATCH} (the document names another location than the device's
 * shop; the receipt is kept at the shop the device is enrolled at), {@code SESSION_UNKNOWN} (no
 * session with that id was applied before the receipt; sessions and receipts share one consumer
 * queue in the device's own order, so the session was lost or never sent), {@code NO_LINES}, {@code DUPLICATE_NUMBER} (another receipt of the same
 * series already has this number: both are kept, and an ALERT audit record
 * {@code RECEIPT_NUMBER_DUPLICATED} names the other document).
 *
 * <p>Every receipt raises its series' high-water mark in the kernel
 * ({@link NumberingService#observeDeviceNumber}, doc 32 section 8), so a till that gets the
 * position's series later (a re-enrolment, a replacement) continues after the highest number
 * central has applied instead of starting again at 1.
 *
 * <p>Guards (the shape a till cannot send if it follows the contract, not business rules): the
 * device's OWN scope at its shop ({@code m6.scope.device_required}); a document id and an issue
 * time ({@code m6.receipt.malformed}). A receipt recorded before is not recorded again (the
 * consumer's inbox is the first guard against a redelivery).
 *
 * <p>Audit {@code RECEIPT_RECORDED}, and {@code RECEIPT_FLAGGED} (REVIEW) when flagged; event
 * {@code receipt.recorded.v1}. Permission: the system applies it with no user, so none is
 * checked; {@code pos.receipt.view} is the code of the slice's receipt read.
 */
@Service
@CommandHandler(permission = "pos.receipt.view")
class RecordReceiptHandler implements Handles<RecordReceipt, UUID> {

    static final String AUDIT_RECORDED = "RECEIPT_RECORDED";
    static final String AUDIT_FLAGGED = "RECEIPT_FLAGGED";
    static final String AUDIT_DUPLICATE = "RECEIPT_NUMBER_DUPLICATED";
    static final String FLAG_DUPLICATE = "DUPLICATE_NUMBER";
    static final String MESSAGE_DUPLICATE = "m6.receipt.duplicate_number";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final NumberingService numbering;

    RecordReceiptHandler(JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, NumberingService numbering) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.numbering = numbering;
    }

    @Override
    @Transactional
    public UUID handle(RecordReceipt command, ScopeContext scope) {
        IngestGuards.requireDeviceScope(scope);
        if (command.documentId() == null || command.issuedAt() == null) {
            throw new ProblemException("m6.receipt.malformed");
        }
        if (recorded(command.documentId())) {
            return command.documentId();
        }

        List<String> flags = new ArrayList<>();
        UUID shop = scope.locationId();
        if (command.locationId() != null && !command.locationId().equals(shop)) {
            flags.add("LOCATION_MISMATCH");
        }
        if (command.sessionId() != null && !sessionKnown(command.sessionId())) {
            flags.add("SESSION_UNKNOWN");
        }
        if (command.lines().isEmpty()) {
            flags.add("NO_LINES");
        }

        // Central keeps the series' high-water mark from what the tills send (doc 32 section 8),
        // so a till newly given this position's series starts after the highest number applied.
        // The update's row lock also serialises two receipts of one series for the check below.
        List<UUID> sameNumber = List.of();
        if (command.seriesId() != null && command.docNumber() != null) {
            // Only the device's own series at its shop moves (wave 2, M6-04: the kernel's half);
            // the flags of a foreign series and a jump are M6's own ticket (wave 2, PR 13).
            numbering.observeDeviceNumber(command.seriesId(), command.docNumber(), scope);
            sameNumber = jdbc.queryForList(
                    "select document_id from pos.receipt where series_id = ? and doc_number = ? and document_id <> ?",
                    UUID.class,
                    command.seriesId(),
                    command.docNumber(),
                    command.documentId());
            if (!sameNumber.isEmpty()) {
                flags.add(FLAG_DUPLICATE);
            }
        }

        jdbc.update(
                """
                insert into pos.receipt (document_id, owner_entity_id, location_id, till_position_id, device_id,
                    session_id, series_id, doc_number, doc_number_display, issued_at, business_date, operator_user_id,
                    currency, net_amount, tax_amount, gross_amount, content_hash, device_seq, flags)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, 'LKR'), ?, ?, ?, ?, ?, ?::text[])
                """,
                command.documentId(),
                scope.entityId(),
                shop,
                command.tillPositionId(),
                scope.deviceId() != null ? scope.deviceId() : command.deviceId(),
                command.sessionId(),
                command.seriesId(),
                command.docNumber(),
                command.docNumberDisplay(),
                Timestamp.from(command.issuedAt()),
                command.businessDate(),
                command.operatorUserId(),
                command.currency(),
                command.netAmount(),
                command.taxAmount(),
                command.grossAmount(),
                command.contentHash(),
                command.deviceSeq(),
                "{" + String.join(",", flags) + "}");
        for (RecordReceipt.Line line : command.lines()) {
            jdbc.update(
                    """
                    insert into pos.receipt_line (document_id, line_no, owner_entity_id, location_id, sku_id, batch_id,
                        uom_code, qty, unit_price, line_total)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    command.documentId(),
                    line.lineNo(),
                    scope.entityId(),
                    shop,
                    line.skuId(),
                    line.batchId(),
                    line.uomCode(),
                    line.qty(),
                    line.unitPrice(),
                    line.lineTotal());
        }
        for (RecordReceipt.Tender tender : command.tenders()) {
            jdbc.update(
                    "insert into pos.receipt_tender (document_id, seq, owner_entity_id, location_id, kind, amount)"
                            + " values (?, ?, ?, ?, ?, ?)",
                    command.documentId(),
                    tender.seq(),
                    scope.entityId(),
                    shop,
                    tender.kind(),
                    tender.amount());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("docNumber", command.docNumberDisplay());
        after.put("locationId", shop);
        after.put("lines", command.lines().size());
        after.put("grossAmount", command.grossAmount());
        audit.record(AUDIT_RECORDED, Subject.of("receipt", command.documentId()), null, after, scope);
        if (!flags.isEmpty()) {
            audit.record(
                    AUDIT_FLAGGED,
                    Subject.of("receipt", command.documentId()),
                    null,
                    Map.of("flags", flags),
                    scope,
                    "Applied and flagged: " + String.join(", ", flags));
        }
        if (!sameNumber.isEmpty()) {
            // Two documents under one number break the series' uniqueness (24B): an ALERT for a
            // person, kept apart from the REVIEW flag record so that it reaches the alert list.
            Map<String, Object> duplicate = new LinkedHashMap<>();
            duplicate.put("messageId", MESSAGE_DUPLICATE);
            duplicate.put("seriesId", command.seriesId());
            duplicate.put("docNumber", command.docNumberDisplay());
            duplicate.put("otherDocumentIds", sameNumber);
            duplicate.put("deviceId", scope.deviceId() != null ? scope.deviceId() : command.deviceId());
            audit.record(
                    AUDIT_DUPLICATE,
                    Subject.of("receipt", command.documentId()),
                    null,
                    duplicate,
                    scope,
                    "Applied and flagged: another receipt already has number " + command.docNumberDisplay());
        }
        events.publish(new ReceiptRecorded(
                command.documentId(),
                scope.entityId(),
                shop,
                command.docNumberDisplay(),
                command.grossAmount(),
                command.lines().size(),
                List.copyOf(flags)));
        return command.documentId();
    }

    private boolean recorded(UUID documentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pos.receipt where document_id = ?)", Boolean.class, documentId));
    }

    private boolean sessionKnown(UUID sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pos.till_session where session_id = ?)", Boolean.class, sessionId));
    }
}
