package lk.coopfed.knoweb.m2catalogue.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * CorrectBatchMrp / CorrectBatchExpiry of 22A section 6 as one command: a mis-keyed printed MRP
 * or expiry is corrected by a replacement batch that cites the old one, never by an edit (doc 22
 * section 3.7, B-I8). At least one of the two values is given, and it differs from the batch's.
 */
public record CorrectBatch(
        UUID batchId, BigDecimal printedMrp, LocalDate expiryDate, String reasonCode, String reasonText) {}
