package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One ACCOUNT tender of a till receipt, posted to the customer's account (27A section 6.2): a
 * CHARGE for a sale (receipt.issued.v1), a CREDIT for a refund. The receipt tender consumer
 * builds it from the till's bundle; the demo loader posts its history through the same handler.
 * A fact from a till is never refused (AGENTS.md): a breach of the limit is a flag and a REVIEW.
 *
 * @param kind          CHARGE or CREDIT
 * @param amount        the tender's amount, above zero; the posting carries the sign
 * @param receiptNumber the receipt's display number, shown on the statement
 */
public record PostAccountTender(
        String kind,
        UUID accountId,
        BigDecimal amount,
        UUID receiptDocumentId,
        String receiptNumber,
        int tenderSeq,
        UUID locationId,
        LocalDate businessDate,
        UUID operatorUserId,
        boolean offline) {

    public static final String CHARGE = "CHARGE";
    public static final String CREDIT = "CREDIT";
}
