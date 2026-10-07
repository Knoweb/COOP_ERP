package lk.coopfed.knoweb.m4trading.internal.queries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.query.DebitNoteQueries;
import lk.coopfed.knoweb.m4trading.query.DebitNoteView;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The debit notes, from the document base and {@code trading.doc_debit_note}. The seller
 * owns a debit note and the buyer is its counterparty; both read it through document_read. Its
 * lines carry no cost, like an invoice's.
 */
@Service
class DebitNoteQueriesImpl implements DebitNoteQueries {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;

    DebitNoteQueriesImpl(JdbcTemplate jdbc, DocumentBaseRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DebitNoteView> getDebitNote(UUID debitNoteId, ScopeContext scope) {
        if (debitNoteId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select invoice_document_id, reason
                  from trading.doc_debit_note where document_id = ?
                """,
                debitNoteId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return documents.findById(debitNoteId).map(header -> view(header, row));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebitNoteView> debitNotesOf(UUID invoiceId, ScopeContext scope) {
        if (invoiceId == null) {
            return List.of();
        }
        List<DebitNoteView> views = new ArrayList<>();
        for (UUID id : jdbc.queryForList(
                "select document_id from trading.doc_debit_note where invoice_document_id = ? order by document_id",
                UUID.class,
                invoiceId)) {
            getDebitNote(id, scope).ifPresent(views::add);
        }
        return views;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> printObjectKey(UUID debitNoteId, ScopeContext scope) {
        if (debitNoteId == null) {
            return Optional.empty();
        }
        return jdbc
                .queryForList(
                        "select print_object_key from trading.doc_debit_note where document_id = ?",
                        String.class,
                        debitNoteId)
                .stream()
                .filter(key -> key != null)
                .findFirst();
    }

    private DebitNoteView view(DocumentRecord header, Map<String, Object> row) {
        UUID invoiceId = (UUID) row.get("invoice_document_id");
        return new DebitNoteView(
                header.id(),
                header.docNumberDisplay(),
                header.status(),
                invoiceId,
                documents
                        .findById(invoiceId)
                        .map(DocumentRecord::docNumberDisplay)
                        .orElse(null),
                header.ownerEntityId(),
                header.counterpartyEntityId(),
                (String) row.get("reason"),
                header.issuedAt(),
                header.netAmount(),
                header.taxAmount(),
                header.grossAmount(),
                documents.findLines(header.id()).stream()
                        .map(line -> new InvoiceView.InvoiceLineView(
                                line.id(),
                                line.lineNo(),
                                line.skuId(),
                                line.batchId(),
                                line.uomCode(),
                                line.qty(),
                                line.unitPrice(),
                                line.taxRatePercent(),
                                line.taxAmount(),
                                line.lineTotal(),
                                line.referenceLineId()))
                        .toList());
    }
}
