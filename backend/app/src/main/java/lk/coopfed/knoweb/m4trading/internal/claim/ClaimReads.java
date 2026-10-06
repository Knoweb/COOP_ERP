package lk.coopfed.knoweb.m4trading.internal.claim;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.m4trading.api.ClaimLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The reads the claim handlers and queries share: the buyer's claim (header, extension, lines,
 * photographs), the seller's decision on it and the buyer's return. Reads only; the handlers
 * write (ArchitectureTests).
 */
@Component
public class ClaimReads {

    public static final String CLM = "CLM";
    public static final String RAISED = "RAISED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;

    ClaimReads(JdbcTemplate jdbc, DocumentBaseRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    /** The claim as the caller's row-level security shows it, or empty. */
    public Optional<Claim> claim(UUID claimId) {
        if (claimId == null) {
            return Optional.empty();
        }
        Optional<DocumentRecord> header =
                documents.findById(claimId).filter(document -> CLM.equals(document.docTypeCode()));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select grn_document_id, kind, return_requested, note, window_ends_at from trading.doc_claim"
                        + " where document_id = ?",
                claimId);
        if (header.isEmpty() || rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        List<ClaimLine> lines = new ArrayList<>();
        for (Map<String, Object> line : jdbc.queryForList(
                """
                select line_id, grn_line_id, sku_id, batch_id, uom_code, claimed_qty
                  from trading.doc_claim_line
                 where document_id = ? order by line_no
                """,
                claimId)) {
            lines.add(new ClaimLine(
                    (UUID) line.get("line_id"),
                    (UUID) line.get("grn_line_id"),
                    (UUID) line.get("sku_id"),
                    (UUID) line.get("batch_id"),
                    (String) line.get("uom_code"),
                    (BigDecimal) line.get("claimed_qty")));
        }
        List<UUID> photos = jdbc.queryForList(
                "select attachment_id from trading.claim_photo where claim_document_id = ? order by added_at, attachment_id",
                UUID.class,
                claimId);
        return Optional.of(new Claim(
                header.get(),
                (UUID) row.get("grn_document_id"),
                (String) row.get("kind"),
                Boolean.TRUE.equals(row.get("return_requested")),
                (String) row.get("note"),
                ((Timestamp) row.get("window_ends_at")).toInstant(),
                List.copyOf(lines),
                List.copyOf(photos)));
    }

    /** The seller's decision on the claim, or empty while it is undecided. */
    public Optional<Decision> decision(UUID claimId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select decision, findings, reason, return_required, credit_note_document_id, decided_by, decided_at
                  from trading.claim_decision where claim_document_id = ?
                """,
                claimId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        Map<UUID, BigDecimal> approved = new java.util.LinkedHashMap<>();
        for (Map<String, Object> line : jdbc.queryForList(
                "select claim_line_id, approved_qty from trading.claim_decision_line where claim_document_id = ?",
                claimId)) {
            approved.put((UUID) line.get("claim_line_id"), (BigDecimal) line.get("approved_qty"));
        }
        return Optional.of(new Decision(
                (String) row.get("decision"),
                (String) row.get("findings"),
                (String) row.get("reason"),
                Boolean.TRUE.equals(row.get("return_required")),
                (UUID) row.get("credit_note_document_id"),
                (UUID) row.get("decided_by"),
                ((Timestamp) row.get("decided_at")).toInstant(),
                Map.copyOf(approved)));
    }

    /** When the buyer sent the goods back, or empty. */
    public Optional<Instant> returnedAt(UUID claimId) {
        return jdbc
                .queryForList(
                        "select dispatched_at from trading.claim_return where claim_document_id = ?",
                        Timestamp.class,
                        claimId)
                .stream()
                .findFirst()
                .map(Timestamp::toInstant);
    }

    /**
     * What earlier claims hold of a GRN line, so a line is not claimed twice over: an undecided
     * claim holds what it claimed, an approved one only what the seller approved, a rejected one
     * nothing. A partial approval decides the approved quantity only, and the rest is free to
     * claim again, as a rejection frees the whole (decision A-2, wave 2 M4MONEY-13); the seller
     * decides every re-claim, and the per-line credit cap ({@code InvoiceCredits}) stops a double
     * credit whatever is claimed.
     */
    public BigDecimal claimedBefore(UUID grnLineId) {
        BigDecimal held = jdbc.queryForObject(
                """
                select coalesce(sum(case
                           when d.decision = 'REJECTED' then 0
                           when d.decision = 'APPROVED' then coalesce(dl.approved_qty, 0)
                           else l.claimed_qty end), 0)
                  from trading.doc_claim_line l
                  left join trading.claim_decision d on d.claim_document_id = l.document_id
                  left join trading.claim_decision_line dl on dl.claim_line_id = l.line_id
                 where l.grn_line_id = ?
                """,
                BigDecimal.class,
                grnLineId);
        return held == null ? BigDecimal.ZERO : held;
    }

    public record Claim(
            DocumentRecord header,
            UUID grnId,
            String kind,
            boolean returnRequested,
            String note,
            Instant windowEndsAt,
            List<ClaimLine> lines,
            List<UUID> photoIds) {}

    public record Decision(
            String decision,
            String findings,
            String reason,
            boolean returnRequired,
            UUID creditNoteId,
            UUID decidedBy,
            Instant decidedAt,
            Map<UUID, BigDecimal> approvedByLine) {}
}
