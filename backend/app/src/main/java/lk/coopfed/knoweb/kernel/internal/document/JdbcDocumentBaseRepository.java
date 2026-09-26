package lk.coopfed.knoweb.kernel.internal.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinkRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentStateHistoryRecord;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * The document base on {@code kernel.document} and its rows (doc 18 part C; 19A section 7),
 * replacing the 17A in-memory stub. Headers are immutable once issued except for status, and
 * the status changes only through {@link #addStateTransition}: {@link #save} refuses anything
 * else with {@code document.immutable}, and the database trigger refuses it again for anybody
 * who bypasses this class. Lines, links and history are insert-only, by grant and by trigger,
 * and no line joins an issued document (the trigger and the policy of V0055).
 *
 * <p>Only the issuance protocol numbers a document: {@link #save} refuses a record that
 * carries a number, an issuance time or a hash ({@code document.issued_fields_reserved}), and
 * the protocol writes those through the package-private {@link #writeIssued}.
 *
 * <p>Runs in the caller's transaction and scope: row-level security decides what a caller
 * sees and may write, so a document of another entity is simply not found.
 */
@Component
class JdbcDocumentBaseRepository implements DocumentBaseRepository {

    private static final String HEADER_COLUMNS =
            "document_id, doc_type_code, series_id, doc_number, doc_number_display,"
                    + " owner_entity_id, counterparty_entity_id, location_id, till_position_id, device_id, status, issued_at,"
                    + " issued_local, business_date, operator_user_id, currency, net_amount, tax_amount, gross_amount,"
                    + " reference_document_id, content_hash, origin, device_seq, notes";

    static final RowMapper<DocumentRecord> HEADER = (ResultSet rs, int rowNum) -> new DocumentRecord(
            uuid(rs, "document_id"),
            rs.getString("doc_type_code"),
            uuid(rs, "series_id"),
            rs.getObject("doc_number", Long.class),
            rs.getString("doc_number_display"),
            uuid(rs, "owner_entity_id"),
            uuid(rs, "counterparty_entity_id"),
            uuid(rs, "location_id"),
            uuid(rs, "till_position_id"),
            uuid(rs, "device_id"),
            rs.getString("status"),
            instant(rs, "issued_at"),
            rs.getObject("issued_local", LocalDateTime.class),
            rs.getObject("business_date", LocalDate.class),
            uuid(rs, "operator_user_id"),
            rs.getString("currency"),
            rs.getBigDecimal("net_amount"),
            rs.getBigDecimal("tax_amount"),
            rs.getBigDecimal("gross_amount"),
            uuid(rs, "reference_document_id"),
            rs.getString("content_hash"),
            DocumentOrigin.valueOf(rs.getString("origin")),
            rs.getObject("device_seq", Long.class),
            rs.getString("notes"));

    private static final String LINE_COLUMNS =
            "document_line_id, document_id, line_no, sku_id, batch_id, uom_code, qty,"
                    + " unit_price, mrp_applied, control_price_applied, cap_reason, discount_rule_id, discount_amount,"
                    + " tax_rate_percent, tax_amount, line_total, unit_cost_at_issue, loss_category, reference_line_id";

    static final RowMapper<DocumentLineRecord> LINE = (ResultSet rs, int rowNum) -> new DocumentLineRecord(
            uuid(rs, "document_line_id"),
            uuid(rs, "document_id"),
            rs.getInt("line_no"),
            uuid(rs, "sku_id"),
            uuid(rs, "batch_id"),
            rs.getString("uom_code"),
            rs.getBigDecimal("qty"),
            rs.getBigDecimal("unit_price"),
            rs.getBigDecimal("mrp_applied"),
            rs.getBigDecimal("control_price_applied"),
            rs.getString("cap_reason"),
            uuid(rs, "discount_rule_id"),
            rs.getBigDecimal("discount_amount"),
            rs.getBigDecimal("tax_rate_percent"),
            rs.getBigDecimal("tax_amount"),
            rs.getBigDecimal("line_total"),
            rs.getBigDecimal("unit_cost_at_issue"),
            rs.getString("loss_category"),
            uuid(rs, "reference_line_id"));

    static final RowMapper<DocumentLinkRecord> LINK = (ResultSet rs, int rowNum) -> new DocumentLinkRecord(
            uuid(rs, "from_document_id"),
            uuid(rs, "to_document_id"),
            LinkType.valueOf(rs.getString("link_type")),
            rs.getBigDecimal("amount"),
            instant(rs, "created_at"),
            uuid(rs, "created_by"));

    static final RowMapper<DocumentStateHistoryRecord> HISTORY =
            (ResultSet rs, int rowNum) -> new DocumentStateHistoryRecord(
                    uuid(rs, "history_id"),
                    uuid(rs, "document_id"),
                    rs.getString("from_status"),
                    rs.getString("to_status"),
                    instant(rs, "occurred_at"),
                    rs.getObject("occurred_local", LocalDateTime.class),
                    uuid(rs, "actor_user_id"),
                    uuid(rs, "device_id"),
                    rs.getString("reason_code"),
                    rs.getString("reason_text"));

    static final String AUDIT_STATUS_CHANGED = "DOCUMENT_STATUS_CHANGED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;

    JdbcDocumentBaseRepository(JdbcTemplate jdbc, AuditFacade audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    public DocumentRecord save(DocumentRecord document) {
        Optional<DocumentRecord> existing = findById(document.id());

        if (existing.isPresent() && existing.get().isIssued()) {
            throw new ProblemException("document.immutable");
        }

        if (carriesIssuedFields(document)) {
            throw new ProblemException("document.issued_fields_reserved");
        }

        if (existing.isEmpty()) {
            insertHeader(document);
            return document;
        }

        updateDraft(document);
        return document;
    }

    /** What only the issuance protocol sets (doc 18 section 5.5): a caller's record never carries them. */
    private static boolean carriesIssuedFields(DocumentRecord d) {
        return d.seriesId() != null
                || d.docNumber() != null
                || d.docNumberDisplay() != null
                || d.issuedAt() != null
                || d.issuedLocal() != null
                || d.contentHash() != null;
    }

    /**
     * The issuance protocol's write: the stored draft becomes the issued header. Package-private
     * so that no module issues a document around the protocol.
     */
    void writeIssued(DocumentRecord issued) {
        updateDraft(issued);
    }

    @Override
    public void saveLines(UUID documentId, List<DocumentLineRecord> lines) {
        for (DocumentLineRecord line : lines) {
            if (!documentId.equals(line.documentId())) {
                throw new IllegalArgumentException(
                        "Line " + line.lineNo() + " belongs to document " + line.documentId() + ", not " + documentId);
            }
        }

        jdbc.batchUpdate(
                "insert into kernel.document_line (" + LINE_COLUMNS + ") values (" + "?, ".repeat(18) + "?)",
                lines,
                lines.size(),
                (ps, line) -> {
                    ps.setObject(1, line.id());
                    ps.setObject(2, line.documentId());
                    ps.setInt(3, line.lineNo());
                    ps.setObject(4, line.skuId());
                    ps.setObject(5, line.batchId());
                    ps.setString(6, line.uomCode());
                    ps.setBigDecimal(7, line.qty());
                    ps.setBigDecimal(8, line.unitPrice());
                    ps.setBigDecimal(9, line.mrpApplied());
                    ps.setBigDecimal(10, line.controlPriceApplied());
                    ps.setString(11, line.capReason());
                    ps.setObject(12, line.discountRuleId());
                    ps.setBigDecimal(13, line.discountAmount());
                    ps.setBigDecimal(14, line.taxRatePercent());
                    ps.setBigDecimal(15, line.taxAmount());
                    ps.setBigDecimal(16, line.lineTotal());
                    ps.setBigDecimal(17, line.unitCostAtIssue());
                    ps.setString(18, line.lossCategory());
                    ps.setObject(19, line.referenceLineId());
                });
    }

    @Override
    public void addLink(DocumentLinkRecord link) {
        try {
            jdbc.update(
                    """
                    insert into kernel.document_link (from_document_id, to_document_id, link_type, amount, created_at, created_by)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    link.fromDocumentId(),
                    link.toDocumentId(),
                    link.linkType().name(),
                    link.amount(),
                    link.createdAt() == null ? null : Timestamp.from(link.createdAt()),
                    link.createdBy());
        } catch (DataIntegrityViolationException e) {
            // The partial unique index of V0055: a second REVERSES of the same original, however
            // close in time the two came.
            if (String.valueOf(e.getMessage()).contains("document_link_reverses_once_idx")) {
                throw new ProblemException("document.link.reversed_already");
            }
            throw new ProblemException("document.link.exists");
        }
    }

    @Override
    public void lockForLinking(UUID documentId) {
        // A transaction-scoped advisory lock keyed by the document. A correction of a document
        // the caller does not own (a DISPUTES of the seller's invoice) cannot take a row lock on
        // it, because a row lock needs the UPDATE policy, so the lock is by key instead. It is
        // released with the transaction.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", documentId.toString());
    }

    @Override
    public void addStateTransition(DocumentStateHistoryRecord transition, ScopeContext ctx) {
        if (transition.fromStatus() == null
                || transition.toStatus() == null
                || transition.fromStatus().equals(transition.toStatus())) {
            throw new ProblemException("document.status_conflict");
        }

        // Compare-and-set: the header moves only from the status the caller saw. A transition
        // that raced this one, or a stale view of the document, changes no row and is refused,
        // so two acceptances of one document cannot both succeed.
        int moved = jdbc.update(
                "update kernel.document set status = ? where document_id = ? and status = ?",
                transition.toStatus(),
                transition.documentId(),
                transition.fromStatus());

        if (moved != 1) {
            throw new ProblemException(
                    "document.status_conflict",
                    Map.of("fromStatus", transition.fromStatus(), "toStatus", transition.toStatus()));
        }

        insertHistory(transition);

        audit.record(
                AUDIT_STATUS_CHANGED,
                Subject.of("document", transition.documentId()),
                Map.of("status", transition.fromStatus()),
                Map.of("status", transition.toStatus(), "reasonCode", String.valueOf(transition.reasonCode())),
                ctx,
                transition.reasonText());
    }

    /** The history row alone; the issuance protocol writes its DRAFT to ISSUED row through here. */
    void insertHistory(DocumentStateHistoryRecord transition) {
        jdbc.update(
                """
                insert into kernel.document_state_history (
                    history_id, document_id, from_status, to_status, occurred_at, occurred_local,
                    actor_user_id, device_id, reason_code, reason_text
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                transition.id(),
                transition.documentId(),
                transition.fromStatus(),
                transition.toStatus(),
                Timestamp.from(transition.occurredAt()),
                transition.occurredLocal(),
                transition.actorUserId(),
                transition.deviceId(),
                transition.reasonCode(),
                transition.reasonText());
    }

    @Override
    public Optional<DocumentRecord> findById(UUID id) {
        return jdbc
                .query("select " + HEADER_COLUMNS + " from kernel.document where document_id = ?", HEADER, id)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<DocumentRecord> findByIdForUpdate(UUID id) {
        return jdbc
                .query(
                        "select " + HEADER_COLUMNS + " from kernel.document where document_id = ? for update",
                        HEADER,
                        id)
                .stream()
                .findFirst();
    }

    @Override
    public List<DocumentLineRecord> findLines(UUID documentId) {
        return jdbc.query(
                "select " + LINE_COLUMNS + " from kernel.document_line where document_id = ? order by line_no",
                LINE,
                documentId);
    }

    @Override
    public List<DocumentLinkRecord> findLinks(UUID documentId) {
        return jdbc.query(
                """
                select from_document_id, to_document_id, link_type, amount, created_at, created_by
                  from kernel.document_link
                 where from_document_id = ? or to_document_id = ?
                 order by created_at
                """,
                LINK,
                documentId,
                documentId);
    }

    @Override
    public List<DocumentStateHistoryRecord> findHistory(UUID documentId) {
        return jdbc.query(
                """
                select history_id, document_id, from_status, to_status, occurred_at, occurred_local,
                       actor_user_id, device_id, reason_code, reason_text
                  from kernel.document_state_history
                 where document_id = ?
                 order by occurred_at, received_at
                """,
                HISTORY,
                documentId);
    }

    private void insertHeader(DocumentRecord d) {
        jdbc.update("insert into kernel.document (" + HEADER_COLUMNS + ") values (" + "?, ".repeat(23) + "?)", ps -> {
            ps.setObject(1, d.id());
            ps.setString(2, d.docTypeCode());
            ps.setObject(3, d.seriesId());
            ps.setObject(4, d.docNumber(), Types.BIGINT);
            ps.setString(5, d.docNumberDisplay());
            ps.setObject(6, d.ownerEntityId());
            ps.setObject(7, d.counterpartyEntityId());
            ps.setObject(8, d.locationId());
            ps.setObject(9, d.tillPositionId());
            ps.setObject(10, d.deviceId());
            ps.setString(11, d.status());
            ps.setTimestamp(12, d.issuedAt() == null ? null : Timestamp.from(d.issuedAt()));
            ps.setObject(13, d.issuedLocal());
            ps.setObject(14, d.businessDate());
            ps.setObject(15, d.operatorUserId());
            ps.setString(16, d.currency() == null ? "LKR" : d.currency());
            ps.setBigDecimal(17, d.netAmount());
            ps.setBigDecimal(18, d.taxAmount());
            ps.setBigDecimal(19, d.grossAmount());
            ps.setObject(20, d.referenceDocumentId());
            ps.setString(21, d.contentHash());
            ps.setString(22, (d.origin() == null ? DocumentOrigin.ONLINE : d.origin()).name());
            ps.setObject(23, d.deviceSeq(), Types.BIGINT);
            ps.setString(24, d.notes());
        });
    }

    /** A draft may change freely until it is issued; the issuance protocol fills the rest through here too. */
    private void updateDraft(DocumentRecord d) {
        jdbc.update(
                """
                update kernel.document
                   set status = ?, series_id = ?, doc_number = ?, doc_number_display = ?, issued_at = ?,
                       issued_local = ?, business_date = ?, operator_user_id = ?, device_id = ?,
                       net_amount = ?, tax_amount = ?, gross_amount = ?, content_hash = ?, notes = ?
                 where document_id = ?
                """,
                ps -> {
                    ps.setString(1, d.status());
                    ps.setObject(2, d.seriesId());
                    ps.setObject(3, d.docNumber(), Types.BIGINT);
                    ps.setString(4, d.docNumberDisplay());
                    ps.setTimestamp(5, d.issuedAt() == null ? null : Timestamp.from(d.issuedAt()));
                    ps.setObject(6, d.issuedLocal());
                    ps.setObject(7, d.businessDate());
                    ps.setObject(8, d.operatorUserId());
                    ps.setObject(9, d.deviceId());
                    ps.setBigDecimal(10, d.netAmount());
                    ps.setBigDecimal(11, d.taxAmount());
                    ps.setBigDecimal(12, d.grossAmount());
                    ps.setString(13, d.contentHash());
                    ps.setString(14, d.notes());
                    ps.setObject(15, d.id());
                });
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
