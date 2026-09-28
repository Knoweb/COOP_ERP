package lk.coopfed.knoweb.m7customers.internal.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A repayment a till took (27A section 7.3, "CPR bundle (till -> central)"): the CPR the till
 * numbered from its own series, with the account, the method (CASH at a till) and the amount. The
 * consumer {@code m7.repayments} builds it from the till's {@code customer_payment.issued.v1}.
 */
record RecordTillPayment(
        UUID documentId,
        UUID seriesId,
        Long docNumber,
        String docNumberDisplay,
        UUID locationId,
        UUID tillPositionId,
        UUID deviceId,
        LocalDate businessDate,
        UUID operatorUserId,
        UUID accountId,
        String method,
        BigDecimal amount,
        String reference) {}
