package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** payment_receipt.printed.v1 (M4-11): the A4 copy of a recorded payment receipt is in the object store. */
public record PaymentReceiptPrinted(UUID receiptId, UUID sellerEntityId, String objectKey) implements DomainEvent {

    public static final String TYPE = "payment_receipt.printed.v1";
}
