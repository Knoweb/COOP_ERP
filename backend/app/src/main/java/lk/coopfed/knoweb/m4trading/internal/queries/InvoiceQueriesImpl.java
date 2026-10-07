package lk.coopfed.knoweb.m4trading.internal.queries;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.internal.invoice.InvoiceDisputes;
import lk.coopfed.knoweb.m4trading.query.InvoiceBalance;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The invoices, from the document base and {@code trading.doc_invoice}; unpaged for the demo. An
 * invoice line carries no cost (M4 writes none), so the buyer reading it through document_read
 * sees nothing of the seller's margin (the PARTY masking views stay deferred, PLAN_TO_M2).
 */
@Service
class InvoiceQueriesImpl implements InvoiceQueries {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final InvoiceDisputes disputes;

    InvoiceQueriesImpl(JdbcTemplate jdbc, DocumentBaseRepository documents, InvoiceDisputes disputes) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.disputes = disputes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceView> getInvoice(UUID invoiceId, ScopeContext scope) {
        if (invoiceId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select relationship_id, seller_entity_id, buyer_entity_id, grn_document_ids, seller_vat_no, buyer_vat_no,
                       tax_point_date, due_date
                  from trading.doc_invoice where document_id = ?
                """,
                invoiceId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return documents.findById(invoiceId).map(header -> view(header, row));
    }

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceView> listInvoices(OrderQueries.Role role, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        String column = role == OrderQueries.Role.BUYER ? "buyer_entity_id" : "seller_entity_id";
        List<InvoiceView> views = new ArrayList<>();
        for (UUID id : jdbc.queryForList(
                "select document_id from trading.doc_invoice where " + column + " = ? order by tax_point_date desc",
                UUID.class,
                scope.entityId())) {
            getInvoice(id, scope).ifPresent(views::add);
        }
        return views;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> printObjectKey(UUID invoiceId, ScopeContext scope) {
        if (invoiceId == null) {
            return Optional.empty();
        }
        return jdbc
                .queryForList(
                        "select print_object_key from trading.doc_invoice where document_id = ?",
                        String.class,
                        invoiceId)
                .stream()
                .filter(key -> key != null)
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceBalance> balance(UUID invoiceId, ScopeContext scope) {
        if (invoiceId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select credited_amount, debited_amount, settled_amount from trading.doc_invoice where document_id = ?",
                invoiceId);
        Optional<DocumentRecord> header = documents.findById(invoiceId);
        if (rows.isEmpty() || header.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal credited = (BigDecimal) rows.get(0).get("credited_amount");
        BigDecimal debited = (BigDecimal) rows.get(0).get("debited_amount");
        BigDecimal settled = (BigDecimal) rows.get(0).get("settled_amount");
        BigDecimal gross = header.get().grossAmount() == null
                ? BigDecimal.ZERO
                : header.get().grossAmount();
        Optional<InvoiceDisputes.Latest> dispute = disputes.latest(invoiceId);
        boolean disputed = dispute.map(latest -> InvoiceDisputes.DISPUTED.equals(latest.action()))
                .orElse(false);
        BigDecimal due = gross.subtract(credited).add(debited).subtract(settled);
        return Optional.of(new InvoiceBalance(
                invoiceId,
                credited,
                debited,
                settled,
                due,
                disputed,
                disputed ? dispute.get().reason() : null,
                InvoiceBalance.paymentState(settled, due)));
    }

    private InvoiceView view(DocumentRecord header, Map<String, Object> row) {
        return new InvoiceView(
                header.id(),
                header.docNumberDisplay(),
                header.status(),
                (UUID) row.get("relationship_id"),
                (UUID) row.get("seller_entity_id"),
                (UUID) row.get("buyer_entity_id"),
                (String) row.get("seller_vat_no"),
                (String) row.get("buyer_vat_no"),
                uuids(row.get("grn_document_ids")),
                ((java.sql.Date) row.get("tax_point_date")).toLocalDate(),
                ((java.sql.Date) row.get("due_date")).toLocalDate(),
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

    private static List<UUID> uuids(Object array) {
        try {
            if (array instanceof java.sql.Array sql) {
                return Arrays.stream((Object[]) sql.getArray())
                        .map(UUID.class::cast)
                        .toList();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return List.of();
    }
}
