package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A repayment recorded at the society office (27A section 6, RecordCustomerPayment): a CPR from
 * the society's ENTITY series, a PAYMENT posting, and its allocation to the open charges, oldest
 * first or as chosen.
 *
 * @param method         CASH, TRANSFER or DEPOSIT
 * @param allocationMode OLDEST_FIRST (null) or SPECIFIC
 * @param specific       the charges to settle and how much of each, with SPECIFIC
 */
public record RecordCustomerPayment(
        UUID accountId,
        String method,
        BigDecimal amount,
        String reference,
        String allocationMode,
        List<Specific> specific) {

    public static final String OLDEST_FIRST = "OLDEST_FIRST";
    public static final String SPECIFIC = "SPECIFIC";

    public RecordCustomerPayment {
        specific = specific == null ? List.of() : List.copyOf(specific);
    }

    /** One chosen charge and the amount of the payment to put against it. */
    public record Specific(UUID chargePostingId, BigDecimal amount) {}
}
