package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * payment_receipt.reversed.v1 (doc 24 section 5.3): a receipt reversed by a PRC reversal, and the
 * invoices it had settled reopened by the amounts named.
 */
public record PaymentReceiptReversed(
        UUID reversalId,
        String docNumberDisplay,
        UUID receiptId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        BigDecimal amount,
        List<RecordPaymentReceipt.Settlement> reopened,
        String reason)
        implements DomainEvent {

    public static final String TYPE = "payment_receipt.reversed.v1";
}
