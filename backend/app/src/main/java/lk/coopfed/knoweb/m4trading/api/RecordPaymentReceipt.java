package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RecordPaymentReceipt (24A section 6.3): the seller's accounts record a payment received from a
 * buyer and settle open invoices with it. With no settlements the receipt settles the buyer's open,
 * undisputed invoices oldest first; what is left of the amount stays on the buyer's account
 * (unapplied, on account).
 *
 * @param method CASH, CHEQUE, TRANSFER or DEPOSIT; a CHEQUE names its cheque
 */
public record RecordPaymentReceipt(
        UUID buyerEntityId,
        String method,
        BigDecimal amount,
        String reference,
        LocalDate receivedOn,
        Cheque cheque,
        List<Settlement> settlements) {

    public static final String CHEQUE = "CHEQUE";

    public RecordPaymentReceipt {
        settlements = settlements == null ? List.of() : List.copyOf(settlements);
    }

    /** The cheque a receipt was paid by. */
    public record Cheque(String bank, String chequeNo, LocalDate dated) {}

    /** An amount of the receipt applied to one invoice. */
    public record Settlement(UUID invoiceId, BigDecimal amount) {}
}
