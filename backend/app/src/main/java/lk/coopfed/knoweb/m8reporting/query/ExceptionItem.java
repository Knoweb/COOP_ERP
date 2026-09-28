package lk.coopfed.knoweb.m8reporting.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One item of the exception queue (28A section 7, "exception queue queries"; section 8,
 * "Exception queue"): something that needs a person, worked out from the projections in the
 * caller's scope.
 *
 * @param kind                 DISCREPANCY_OPEN, INVOICE_DISPUTED, CHEQUE_BOUNCED, EXPOSURE_WARNING or
 *                             NEGATIVE_STOCK
 * @param severity             ALERT or REVIEW (doc 19's audit severities)
 * @param subjectId            the discrepancy, invoice, payment receipt, relationship, or the lot's batch
 * @param documentNumber       the document's number, when it has one
 * @param role                 SELLER or BUYER: the caller's side of a trading item; null for stock
 * @param counterpartyEntityId the other party of a trading item
 * @param counterparty         its name in the caller's language
 * @param subject              for stock: the item and the location, as text
 * @param amount               the money at stake (the cheque, the invoice, the exposure), or the
 *                             quantity below zero for stock
 * @param percent              for an exposure warning: the exposure as a percentage of the limit
 * @param since                when the exception arose
 * @param escalated            older than {@code reporting.exception_escalate_after}, or a
 *                             discrepancy past its window
 */
public record ExceptionItem(
        String kind,
        String severity,
        UUID subjectId,
        String documentNumber,
        String role,
        UUID counterpartyEntityId,
        String counterparty,
        String subject,
        BigDecimal amount,
        BigDecimal percent,
        Instant since,
        boolean escalated) {}
