package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * payment_receipt.applied.v1 (24A section 6.3, ApplyReceipt): money a receipt held on the buyer's
 * account settled later invoices. No money moved and no document was issued: the receipt's
 * allocation rows grew, and what it holds on account shrank by {@code appliedAmount}.
 *
 * @param applicationId  names this application (the allocation rows it wrote carry it)
 * @param settlements    what it settled of each invoice
 * @param unappliedAmount what the receipt still holds on account after it
 */
public record PaymentReceiptApplied(
        UUID applicationId,
        UUID receiptId,
        String docNumberDisplay,
        UUID relationshipId,
        UUID sellerEntityId,
        UUID buyerEntityId,
        LocalDate appliedOn,
        BigDecimal appliedAmount,
        List<RecordPaymentReceipt.Settlement> settlements,
        BigDecimal unappliedAmount)
        implements DomainEvent {

    public static final String TYPE = "payment_receipt.applied.v1";
}
