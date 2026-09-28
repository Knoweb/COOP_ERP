package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The payment receipts of doc 24 section 5.2; row-level security decides what a caller sees. */
public interface PaymentQueries {

    Optional<PaymentReceiptView> getReceipt(UUID receiptId, ScopeContext scope);

    /** The receipts the caller's entity issued (SELLER) or that record its payments (BUYER), newest first. */
    List<PaymentReceiptView> listReceipts(OrderQueries.Role role, ScopeContext scope);

    /** The receipts (and reversals) that settled part of an invoice, oldest first. */
    List<PaymentReceiptView> receiptsOf(UUID invoiceId, ScopeContext scope);

    /** Where the printed A4 copy of a receipt is; empty until the worker has printed it. */
    Optional<String> printObjectKey(UUID receiptId, ScopeContext scope);
}
