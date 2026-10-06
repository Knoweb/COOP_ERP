package lk.coopfed.knoweb.m4trading.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment receipt (PRC) as both parties read it (24A section 6.3): what was received, how, what
 * it settled of each invoice and what stays on account. A reversal (a bounced cheque) is a PRC of
 * its own that names the receipt it reverses ({@code reversalOf}); the receipt it reversed names
 * it in {@code reversedBy} and has nothing unapplied.
 *
 * @param status RECORDED, REVERSED (a reversal answers it) or REVERSAL
 */
public record PaymentReceiptView(
        UUID receiptId,
        String docNumberDisplay,
        String status,
        UUID sellerEntityId,
        UUID buyerEntityId,
        String method,
        String reference,
        LocalDate receivedOn,
        Instant issuedAt,
        BigDecimal amount,
        BigDecimal unappliedAmount,
        UUID reversalOf,
        UUID reversedBy,
        String reason,
        ChequeView cheque,
        List<AllocationView> allocations) {

    public static final String RECORDED = "RECORDED";
    public static final String REVERSED = "REVERSED";
    public static final String REVERSAL = "REVERSAL";

    /** The cheque, and its outcome once recorded (null until then). */
    public record ChequeView(String bank, String chequeNo, LocalDate dated, String outcome, Instant outcomeAt) {}

    /** What the receipt settled of one invoice; negative on a reversal. */
    public record AllocationView(UUID invoiceId, String invoiceNumber, BigDecimal amount) {}
}
