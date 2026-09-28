package lk.coopfed.knoweb.m4trading.internal.queries;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads;
import lk.coopfed.knoweb.m4trading.query.DiscrepancyQueries;
import lk.coopfed.knoweb.m4trading.query.DiscrepancyView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The discrepancies, from the document base and {@code trading.doc_discrepancy}; unpaged for the
 * demo. The buyer owns the discrepancy and the seller is its counterparty, so both read it through
 * document_read. It is SETTLED once a credit note names it ({@code trading.doc_credit_note}, V0005):
 * the seller never writes the buyer's discrepancy (AGENTS.md idea 3).
 */
@Service
class DiscrepancyQueriesImpl implements DiscrepancyQueries {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;

    DiscrepancyQueriesImpl(JdbcTemplate jdbc, DocumentBaseRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DiscrepancyView> getDiscrepancy(UUID discrepancyId, ScopeContext scope) {
        if (discrepancyId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select grn_document_id, delivery_document_id, kind, window_ends_at
                  from trading.doc_discrepancy where document_id = ?
                """,
                discrepancyId);
        Optional<DocumentRecord> header =
                documents.findById(discrepancyId).filter(document -> GrnReads.DISC.equals(document.docTypeCode()));
        if (rows.isEmpty() || header.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        UUID grnId = (UUID) row.get("grn_document_id");
        UUID invoiceId = jdbc
                .queryForList(
                        "select document_id from trading.doc_invoice where ? = any (grn_document_ids)",
                        UUID.class,
                        grnId)
                .stream()
                .findFirst()
                .orElse(null);
        UUID creditNoteId = jdbc
                .queryForList(
                        "select document_id from trading.doc_credit_note where discrepancy_document_id = ?",
                        UUID.class,
                        discrepancyId)
                .stream()
                .findFirst()
                .orElse(null);

        Map<UUID, DocumentLineRecord> reported = new LinkedHashMap<>();
        for (DocumentLineRecord line : documents.findLines(discrepancyId)) {
            reported.put(line.referenceLineId(), line);
        }
        List<DiscrepancyView.DiscrepancyLineView> lines = new ArrayList<>();
        for (Map<String, Object> line : jdbc.queryForList(
                """
                select line_id, grn_line_id, expected_qty, received_qty, damaged_qty, variance_qty
                  from trading.doc_discrepancy_line where document_id = ?
                """,
                discrepancyId)) {
            UUID grnLineId = (UUID) line.get("grn_line_id");
            DocumentLineRecord kernelLine = reported.get(grnLineId);
            lines.add(new DiscrepancyView.DiscrepancyLineView(
                    (UUID) line.get("line_id"),
                    grnLineId,
                    kernelLine == null ? null : kernelLine.skuId(),
                    kernelLine == null ? null : kernelLine.batchId(),
                    kernelLine == null ? null : kernelLine.uomCode(),
                    (BigDecimal) line.get("expected_qty"),
                    (BigDecimal) line.get("received_qty"),
                    (BigDecimal) line.get("damaged_qty"),
                    (BigDecimal) line.get("variance_qty"),
                    kernelLine == null ? null : kernelLine.unitPrice()));
        }
        lines.sort(java.util.Comparator.comparing(
                view -> view.skuId() == null ? "" : view.skuId().toString()));

        DocumentRecord disc = header.get();
        return Optional.of(new DiscrepancyView(
                disc.id(),
                disc.docNumberDisplay(),
                creditNoteId == null ? DiscrepancyView.RAISED : DiscrepancyView.SETTLED,
                (String) row.get("kind"),
                disc.ownerEntityId(),
                disc.counterpartyEntityId(),
                grnId,
                documents.findById(grnId).map(DocumentRecord::docNumberDisplay).orElse(null),
                (UUID) row.get("delivery_document_id"),
                disc.issuedAt(),
                ((Timestamp) row.get("window_ends_at")).toInstant(),
                invoiceId,
                creditNoteId,
                creditNoteId == null
                        ? null
                        : documents
                                .findById(creditNoteId)
                                .map(DocumentRecord::docNumberDisplay)
                                .orElse(null),
                List.copyOf(lines)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscrepancyView> listDiscrepancies(OrderQueries.Role role, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        // The buyer (the GRN's receiver) raised the discrepancy with the GRN's seller.
        String column = role == OrderQueries.Role.SELLER ? "g.seller_entity_id" : "g.receiver_entity_id";
        List<DiscrepancyView> views = new ArrayList<>();
        for (UUID id : jdbc.queryForList(
                "select d.document_id from trading.doc_discrepancy d"
                        + " join trading.doc_grn g on g.document_id = d.grn_document_id"
                        + " where " + column + " = ? order by d.window_ends_at desc",
                UUID.class,
                scope.entityId())) {
            getDiscrepancy(id, scope).ifPresent(views::add);
        }
        return views;
    }
}
