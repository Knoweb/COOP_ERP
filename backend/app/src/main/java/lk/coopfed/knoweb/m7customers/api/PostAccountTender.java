package lk.coopfed.knoweb.m7customers.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One ACCOUNT tender of a till receipt, posted to the customer's account (27A section 6.2): a
 * CHARGE for a sale (receipt.issued.v1), a CREDIT for a refund or a void. The receipt tender
 * consumer builds it from the till's bundle; the demo loader posts its history through the same
 * handler. A fact from a till is never refused (AGENTS.md): a breach of the limit is a flag and a
 * REVIEW, and so is a tender central cannot post (wave 2, M7CR-06): the consumer hands over what
 * it could read and {@code null} for what it could not, and the handler flags the tender on the
 * receipt instead of posting it.
 *
 * @param kind          CHARGE or CREDIT
 * @param amount        the tender's amount, above zero in cents; the posting carries the sign
 * @param receiptNumber the receipt's display number, shown on the statement
 * @param tenderSeq     the tender's number on the receipt; null when the till sent none
 */
public record PostAccountTender(
        String kind,
        UUID accountId,
        BigDecimal amount,
        UUID receiptDocumentId,
        String receiptNumber,
        Integer tenderSeq,
        UUID locationId,
        LocalDate businessDate,
        UUID operatorUserId,
        boolean offline) {

    public static final String CHARGE = "CHARGE";
    public static final String CREDIT = "CREDIT";
}
