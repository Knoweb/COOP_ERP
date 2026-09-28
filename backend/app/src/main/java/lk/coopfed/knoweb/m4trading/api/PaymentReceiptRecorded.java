package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * payment_receipt.recorded.v1 (doc 24 section 5.3): the seller received a payment from the buyer;
 * {@code settlements} are what it settled of each invoice, {@code unappliedAmount} what stays on
 * the buyer's account.
 */
public record PaymentReceiptRecorded(
        UUID receiptId,
        String docNumberDisplay,
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        String method,
        BigDecimal amount,
        LocalDate receivedOn,
        List<RecordPaymentReceipt.Settlement> settlements,
        BigDecimal unappliedAmount)
        implements DomainEvent {

    public static final String TYPE = "payment_receipt.recorded.v1";
}
