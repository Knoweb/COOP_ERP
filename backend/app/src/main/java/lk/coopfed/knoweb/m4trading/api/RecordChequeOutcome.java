package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/**
 * RecordChequeOutcome (24A section 6.3): the cheque of a receipt CLEARED or BOUNCED. A bounced
 * cheque reverses the receipt (a PRC reversal, MFA waived as a system consequence) and reopens the
 * invoices it settled.
 */
public record RecordChequeOutcome(UUID receiptId, String outcome, String reason) {

    public static final String CLEARED = "CLEARED";
    public static final String BOUNCED = "BOUNCED";
}
