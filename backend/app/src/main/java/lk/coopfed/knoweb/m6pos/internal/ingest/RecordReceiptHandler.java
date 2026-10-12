package lk.coopfed.knoweb.m6pos.internal.ingest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
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
 * queue in the device's own order, so the session was lost or never sent), {@code NO_LINES},
 * {@code DUPLICATE_NUMBER} (another receipt of the same series already has this number: both are
 * kept, and an ALERT audit record {@code RECEIPT_NUMBER_DUPLICATED} names the other document).
 *
 * <p>Wave 2 (M6-02 to M6-05, decided 6 October 2026:
 * docs/progress/deviations/2026-10-06-wave2-till-facts-at-the-gateway.md (2)) adds, never
 * refusing: {@code NO_SESSION} (no session id: the till always sells inside a session),
 * {@code SESSION_CLOSED} (issued after the applied close of its session), {@code TOTALS_MISSING}
 * (no gross), {@code TOTAL_MISMATCH} (gross is not net + tax, or not the sum of the line totals)
 * and {@code TENDER_MISMATCH} (the tenders do not add up to gross), each beyond
 * {@code pos.receipt.total_tolerance} for the whole receipt; {@code SERIES_FOREIGN} (the series
 * is not the device's own at its shop, so the kernel leaves it alone) and {@code NUMBER_JUMP} (the
 * number is more than {@code pos.series.max_jump} past the series' next number: the series moves
 * by that much at most, once per device and series: a later jump from a device that already has
 * one on record is flagged and moves nothing (TILLM6-08); an ALERT {@code RECEIPT_NUMBER_JUMPED}
 * is written each time). A document id that
 * arrives again with another content hash keeps the first copy (as M5 does) and writes the ALERT
 * {@code RECEIPT_REPLAY_DIFFERS} naming both hashes.
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
    static final String AUDIT_REPLAY_DIFFERS = "RECEIPT_REPLAY_DIFFERS";
    static final String MESSAGE_REPLAY_DIFFERS = "m6.receipt.replay_differs";
    static final String AUDIT_NUMBER_JUMPED = "RECEIPT_NUMBER_JUMPED";
    static final String MESSAGE_NUMBER_JUMPED = "m6.receipt.number_jump";
    static final String FLAG_SERIES_FOREIGN = "SERIES_FOREIGN";
    static final String FLAG_NUMBER_JUMP = "NUMBER_JUMP";

    /** The whole receipt's tolerance when its totals are compared (money; default 0.01). */
    static final String CONFIG_TOLERANCE = "pos.receipt.total_tolerance";
    /** How far past the series' next number a receipt's number may be before it is a jump. */
    static final String CONFIG_MAX_JUMP = "pos.series.max_jump";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final NumberingService numbering;
    private final ConfigRegistry config;

    RecordReceiptHandler(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            NumberingService numbering,
            ConfigRegistry config) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.numbering = numbering;
        this.config = config;
    }

    @Override
    @Transactional
    public UUID handle(RecordReceipt command, ScopeContext scope) {
        IngestGuards.requireDeviceScope(scope);
        if (command.documentId() == null || command.issuedAt() == null) {
            throw new ProblemException("m6.receipt.malformed");
        }
        List<String> firstHash = recordedHashes(command.documentId());
        if (!firstHash.isEmpty()) {
            replayed(command, firstHash.getFirst(), scope);
            return command.documentId();
        }

        List<String> flags = new ArrayList<>();
        UUID shop = scope.locationId();
        if (command.locationId() != null && !command.locationId().equals(shop)) {
            flags.add("LOCATION_MISMATCH");
        }
        if (command.sessionId() == null) {
            flags.add("NO_SESSION");
        } else if (!sessionKnown(command.sessionId())) {
            flags.add("SESSION_UNKNOWN");
        } else if (closedBefore(command.sessionId(), command.issuedAt())) {
            flags.add("SESSION_CLOSED");
        }
        if (command.lines().isEmpty()) {
            flags.add("NO_LINES");
        }
        flags.addAll(totalFlags(command, scope));

        // Central keeps the series' high-water mark from what the tills send (doc 32 section 8),
        // so a till newly given this position's series starts after the highest number applied.
        // The update's row lock also serialises two receipts of one series for the check below.
        List<UUID> sameNumber = List.of();
        Long jumpedFrom = null;
        if (command.seriesId() != null && command.docNumber() != null) {
            jumpedFrom = observeNumber(command, scope, flags);
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
        if (jumpedFrom != null) {
            Map<String, Object> jump = new LinkedHashMap<>();
            jump.put("messageId", MESSAGE_NUMBER_JUMPED);
            jump.put("seriesId", command.seriesId());
            jump.put("docNumber", command.docNumber());
            jump.put("nextNumberBefore", jumpedFrom);
            jump.put("maxJump", maxJump(scope));
            jump.put("deviceId", scope.deviceId() != null ? scope.deviceId() : command.deviceId());
            audit.record(
                    AUDIT_NUMBER_JUMPED,
                    Subject.of("receipt", command.documentId()),
                    null,
                    jump,
                    scope,
                    "Applied and flagged: the number is far past the series' next number; the series moved by the"
                            + " limit only");
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

    /**
     * The content hash of the receipt already recorded under this id, as a list of one (the hash
     * may be null), or an empty list when the receipt is new.
     */
    private List<String> recordedHashes(UUID documentId) {
        return jdbc.query(
                "select content_hash from pos.receipt where document_id = ?",
                (rs, n) -> rs.getString("content_hash"),
                documentId);
    }

    /**
     * The same document id again. With the same content it is a redelivery and nothing happens;
     * with another content hash the first copy stays (M5's {@code movementsCiting} keeps the first
     * as well, so the modules agree) and an ALERT names both hashes for a person (M6-03; doc 32
     * section 7).
     */
    private void replayed(RecordReceipt command, String firstHash, ScopeContext scope) {
        if (command.contentHash() == null || Objects.equals(firstHash, command.contentHash())) {
            return;
        }
        Map<String, Object> differs = new LinkedHashMap<>();
        differs.put("messageId", MESSAGE_REPLAY_DIFFERS);
        differs.put("firstContentHash", firstHash);
        differs.put("laterContentHash", command.contentHash());
        differs.put("docNumber", command.docNumberDisplay());
        differs.put("deviceId", scope.deviceId() != null ? scope.deviceId() : command.deviceId());
        audit.record(
                AUDIT_REPLAY_DIFFERS,
                Subject.of("receipt", command.documentId()),
                null,
                differs,
                scope,
                "The receipt arrived again with other content; the first copy is kept");
    }

    /**
     * The money checks of M6-02, each skipped when a side is missing: gross against net + tax and
     * against the sum of the line totals, and the tenders against gross. The till records the
     * tender as the gross (not the cash handed over), so the tenders must equal it.
     */
    private List<String> totalFlags(RecordReceipt command, ScopeContext scope) {
        if (command.grossAmount() == null) {
            return List.of("TOTALS_MISSING");
        }
        BigDecimal tolerance = new BigDecimal(config.getOrDefault(CONFIG_TOLERANCE, scope, "0.01")).abs();
        BigDecimal gross = command.grossAmount();
        List<String> found = new ArrayList<>();
        boolean totalOff = command.netAmount() != null
                && command.taxAmount() != null
                && beyond(gross, command.netAmount().add(command.taxAmount()), tolerance);
        if (!totalOff
                && !command.lines().isEmpty()
                && command.lines().stream().allMatch(line -> line.lineTotal() != null)) {
            BigDecimal lines = command.lines().stream()
                    .map(RecordReceipt.Line::lineTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            totalOff = beyond(gross, lines, tolerance);
        }
        if (totalOff) {
            found.add("TOTAL_MISMATCH");
        }
        if (command.tenders().stream().allMatch(tender -> tender.amount() != null)) {
            BigDecimal tendered = command.tenders().stream()
                    .map(RecordReceipt.Tender::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (beyond(gross, tendered, tolerance)) {
                found.add("TENDER_MISMATCH");
            }
        }
        return found;
    }

    private static boolean beyond(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a.subtract(b).abs().compareTo(tolerance) > 0;
    }

    /**
     * Raises the series' high-water mark in the kernel (doc 32 section 8) for the device's own
     * series only, by at most {@code pos.series.max_jump} (M6-04). The kernel raises to the number
     * it is given and reports the next number it had, so to cap a jump before it happens the first
     * call observes number 1 (which moves nothing but a series never used, and the receipt moves
     * that one anyway) to learn the outcome and the next number; the second observes the receipt's
     * number, or the capped one.
     *
     * @return the series' next number before, when the receipt's number is a jump; else null
     */
    private Long observeNumber(RecordReceipt command, ScopeContext scope, List<String> flags) {
        NumberingService.Observed probe = numbering.observeDeviceNumber(command.seriesId(), 1, scope);
        if (probe.outcome() == NumberingService.Outcome.FOREIGN
                || (probe.outcome() == NumberingService.Outcome.UNKNOWN && scope.deviceId() != null)) {
            // Not the device's own series at its shop, or one the device cannot see: left alone.
            flags.add(FLAG_SERIES_FOREIGN);
            return null;
        }
        if (probe.nextNumberBefore() == null) {
            return null;
        }
        long next = probe.nextNumberBefore();
        long maxJump = maxJump(scope);
        if (command.docNumber() - next > maxJump) {
            flags.add(FLAG_NUMBER_JUMP);
            // The series moves by the cap once per device (TILLM6-08): while the device has a jump
            // on record in this series, a further jump is flagged and moves nothing, so a runaway
            // counter cannot walk the high-water mark on by the cap with every receipt.
            if (!jumpedBefore(command, scope)) {
                numbering.observeDeviceNumber(command.seriesId(), next - 1 + maxJump, scope);
            }
            return next;
        }
        numbering.observeDeviceNumber(command.seriesId(), command.docNumber(), scope);
        return null;
    }

    /** Whether a receipt of this device in this series was flagged NUMBER_JUMP already (TILLM6-08). */
    private boolean jumpedBefore(RecordReceipt command, ScopeContext scope) {
        UUID device = scope.deviceId() != null ? scope.deviceId() : command.deviceId();
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pos.receipt where series_id = ? and device_id = ? and ?::text = any (flags))",
                Boolean.class,
                command.seriesId(),
                device,
                FLAG_NUMBER_JUMP));
    }

    private long maxJump(ScopeContext scope) {
        return Long.parseLong(config.getOrDefault(CONFIG_MAX_JUMP, scope, "10000"));
    }

    private boolean sessionKnown(UUID sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pos.till_session where session_id = ?)", Boolean.class, sessionId));
    }

    /** An applied close of this session earlier than the receipt (M6-05). */
    private boolean closedBefore(UUID sessionId, Instant issuedAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pos.till_session_close where session_id = ? and closed_at < ?)",
                Boolean.class,
                sessionId,
                Timestamp.from(issuedAt)));
    }
}
