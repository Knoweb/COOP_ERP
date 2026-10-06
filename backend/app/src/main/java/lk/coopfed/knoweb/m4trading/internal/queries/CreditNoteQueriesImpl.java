package lk.coopfed.knoweb.m4trading.internal.queries;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceSettlements;
import lk.coopfed.knoweb.m4trading.query.CreditNoteQueries;
import lk.coopfed.knoweb.m4trading.query.CreditNoteView;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The credit notes, from the document base and {@code trading.doc_credit_note} (V0005). The seller
 * owns a credit note and the buyer is its counterparty; both read it through document_read. Its
 * lines carry no cost, like an invoice's.
 */
@Service
class CreditNoteQueriesImpl implements CreditNoteQueries {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final InvoiceSettlements settlements;

    CreditNoteQueriesImpl(JdbcTemplate jdbc, DocumentBaseRepository documents, InvoiceSettlements settlements) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.settlements = settlements;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreditNoteView> getCreditNote(UUID creditNoteId, ScopeContext scope) {
        if (creditNoteId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select invoice_document_id, discrepancy_document_id, reason
                  from trading.doc_credit_note where document_id = ?
                """,
                creditNoteId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return documents.findById(creditNoteId).map(header -> view(header, row));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditNoteView> creditNotesOf(UUID invoiceId, ScopeContext scope) {
        if (invoiceId == null) {
            return List.of();
        }
        List<CreditNoteView> views = new ArrayList<>();
        for (UUID id : jdbc.queryForList(
                "select document_id from trading.doc_credit_note where invoice_document_id = ? order by document_id",
                UUID.class,
                invoiceId)) {
            getCreditNote(id, scope).ifPresent(views::add);
        }
        return views;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditNoteView> unappliedCreditNotes(UUID sellerEntityId, UUID buyerEntityId, ScopeContext scope) {
        if (sellerEntityId == null || buyerEntityId == null) {
            return List.of();
        }
        List<CreditNoteView> views = new ArrayList<>();
        for (UUID id : jdbc.queryForList(
                """
                select c.document_id from trading.doc_credit_note c
                  join trading.doc_invoice i on i.document_id = c.invoice_document_id
                 where i.seller_entity_id = ? and i.buyer_entity_id = ?
                 order by c.document_id
                """,
                UUID.class,
                sellerEntityId,
                buyerEntityId)) {
            getCreditNote(id, scope)
                    .filter(note -> note.unappliedAmount().signum() > 0)
                    .ifPresent(views::add);
        }
        return views;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> printObjectKey(UUID creditNoteId, ScopeContext scope) {
        if (creditNoteId == null) {
            return Optional.empty();
        }
        return jdbc
                .queryForList(
                        "select print_object_key from trading.doc_credit_note where document_id = ?",
                        String.class,
                        creditNoteId)
                .stream()
                .filter(key -> key != null)
                .findFirst();
    }

    private CreditNoteView view(DocumentRecord header, Map<String, Object> row) {
        UUID invoiceId = (UUID) row.get("invoice_document_id");
        BigDecimal gross = header.grossAmount() == null ? BigDecimal.ZERO : header.grossAmount();
        BigDecimal applied = settlements.appliedOf(header.id());
        return new CreditNoteView(
                header.id(),
                header.docNumberDisplay(),
                header.status(),
                invoiceId,
                documents
                        .findById(invoiceId)
                        .map(DocumentRecord::docNumberDisplay)
                        .orElse(null),
                (UUID) row.get("discrepancy_document_id"),
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
                        .toList(),
                applied,
                gross.subtract(applied));
    }
}
