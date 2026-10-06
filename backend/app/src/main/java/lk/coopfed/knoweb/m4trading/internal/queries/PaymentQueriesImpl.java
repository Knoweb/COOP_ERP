package lk.coopfed.knoweb.m4trading.internal.queries;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentReceiptView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment receipts, from the document base and {@code trading.doc_payment_receipt} with its
 * cheque, outcome and allocation rows; unpaged for the demo. The buyer reads them as the
 * counterparty of the seller's receipts (document_read), and the outcome through party_read.
 */
@Service
class PaymentQueriesImpl implements PaymentQueries {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;

    PaymentQueriesImpl(JdbcTemplate jdbc, DocumentBaseRepository documents) {
        this.jdbc = jdbc;
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentReceiptView> getReceipt(UUID receiptId, ScopeContext scope) {
        if (receiptId == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select r.document_id, r.seller_entity_id, r.payer_entity_id, r.method, r.reference, r.received_on,
                       r.amount, r.reversal_of, r.reason,
                       (select x.document_id from trading.doc_payment_receipt x where x.reversal_of = r.document_id)
                           as reversed_by
                  from trading.doc_payment_receipt r where r.document_id = ?
                """,
                receiptId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return documents.findById(receiptId).map(header -> view(header, rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentReceiptView> listReceipts(OrderQueries.Role role, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        String column = role == OrderQueries.Role.BUYER ? "payer_entity_id" : "seller_entity_id";
        return views(jdbc.queryForList(
                "select document_id from trading.doc_payment_receipt where " + column
                        + " = ? order by received_on desc, document_id desc",
                UUID.class,
                scope.entityId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentReceiptView> receiptsOf(UUID invoiceId, ScopeContext scope) {
        if (invoiceId == null) {
            return List.of();
        }
        return views(jdbc.queryForList(
                """
                select receipt_document_id from trading.payment_allocation
                 where invoice_document_id = ? group by receipt_document_id order by receipt_document_id
                """,
                UUID.class,
                invoiceId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> printObjectKey(UUID receiptId, ScopeContext scope) {
        if (receiptId == null) {
            return Optional.empty();
        }
        return jdbc
                .queryForList(
                        "select print_object_key from trading.doc_payment_receipt where document_id = ?",
                        String.class,
                        receiptId)
                .stream()
                .filter(key -> key != null)
                .findFirst();
    }

    private List<PaymentReceiptView> views(List<UUID> ids) {
        List<PaymentReceiptView> views = new ArrayList<>();
        for (UUID id : ids) {
            getReceipt(id, null).ifPresent(views::add);
        }
        return views;
    }

    private PaymentReceiptView view(DocumentRecord header, Map<String, Object> row) {
        UUID receiptId = header.id();
        UUID reversalOf = (UUID) row.get("reversal_of");
        UUID reversedBy = (UUID) row.get("reversed_by");
        BigDecimal amount = (BigDecimal) row.get("amount");
        List<PaymentReceiptView.AllocationView> allocations = new ArrayList<>();
        BigDecimal settled = BigDecimal.ZERO;
        for (Map<String, Object> allocation : jdbc.queryForList(
                """
                select invoice_document_id, amount from trading.payment_allocation
                 where receipt_document_id = ? order by allocation_id
                """,
                receiptId)) {
            UUID invoiceId = (UUID) allocation.get("invoice_document_id");
            BigDecimal allocated = (BigDecimal) allocation.get("amount");
            settled = settled.add(allocated);
            allocations.add(new PaymentReceiptView.AllocationView(
                    invoiceId,
                    documents
                            .findById(invoiceId)
                            .map(DocumentRecord::docNumberDisplay)
                            .orElse(null),
                    allocated));
        }
        String status = reversalOf != null
                ? PaymentReceiptView.REVERSAL
                : reversedBy != null ? PaymentReceiptView.REVERSED : PaymentReceiptView.RECORDED;
        BigDecimal unapplied = PaymentReceiptView.RECORDED.equals(status) ? amount.subtract(settled) : BigDecimal.ZERO;
        return new PaymentReceiptView(
                receiptId,
                header.docNumberDisplay(),
                status,
                (UUID) row.get("seller_entity_id"),
                (UUID) row.get("payer_entity_id"),
                (String) row.get("method"),
                (String) row.get("reference"),
                ((java.sql.Date) row.get("received_on")).toLocalDate(),
                header.issuedAt(),
                amount,
                unapplied,
                reversalOf,
                reversedBy,
                (String) row.get("reason"),
                cheque(reversalOf != null ? reversalOf : receiptId),
                allocations);
    }

    private PaymentReceiptView.ChequeView cheque(UUID receiptId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select c.bank, c.cheque_no, c.dated, o.outcome, o.recorded_at
                  from trading.cheque c
                  left join trading.cheque_outcome o on o.receipt_document_id = c.receipt_document_id
                 where c.receipt_document_id = ?
                """,
                receiptId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Timestamp at = (Timestamp) row.get("recorded_at");
        return new PaymentReceiptView.ChequeView(
                (String) row.get("bank"),
                (String) row.get("cheque_no"),
                ((java.sql.Date) row.get("dated")).toLocalDate(),
                (String) row.get("outcome"),
                at == null ? null : at.toInstant());
    }
}
