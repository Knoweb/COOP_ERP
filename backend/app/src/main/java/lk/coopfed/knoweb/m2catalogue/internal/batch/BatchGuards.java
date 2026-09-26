package lk.coopfed.knoweb.m2catalogue.internal.batch;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The guards the batch handlers share (22A section 6; doc 22 section 3.7). */
final class BatchGuards {

    /** catalogue.batch.batch_no is varchar(40) (22A section 3). */
    static final int BATCH_NO_LENGTH = 40;

    private BatchGuards() {}

    /**
     * An OWN scope of an entity: the row's owner_entity_id is the caller's entity (own_write). A
     * location scope is admitted: a shop confirms its own receipt (M4), and a batch has no
     * location.
     */
    static void requireOwnScope(ScopeContext scope) {
        if (scope == null
                || !scope.hasActiveScope()
                || scope.entityId() == null
                || scope.policyClass() != PolicyClass.OWN) {
            throw new ProblemException("scope.invalid");
        }
    }

    /**
     * The number of a synthetic batch (doc 22 section 3.7: "batch_no = 'S-' + document number +
     * line"), with a hyphen before the line so that line 1 of S-GRN1 and line 11 of S-GRN are
     * different numbers.
     */
    static String syntheticBatchNo(String originDocumentNo, Integer originLine) {
        if (originDocumentNo == null || originDocumentNo.isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "originDocumentNo"));
        }
        if (originLine == null || originLine < 1) {
            throw new ProblemException("request.field.required", Map.of("field", "originLine"));
        }
        return requireLength("S-" + originDocumentNo.strip() + "-" + originLine);
    }

    static String requireLength(String batchNo) {
        if (batchNo.length() > BATCH_NO_LENGTH) {
            throw new ProblemException("m2.batch.batch_no_too_long", Map.of("length", BATCH_NO_LENGTH));
        }
        return batchNo;
    }

    /** 22A section 6: "MRP present when has_printed_mrp"; an MRP given is a positive amount. */
    static void requireMrp(BigDecimal printedMrp, boolean hasPrintedMrp) {
        if (printedMrp == null) {
            if (hasPrintedMrp) {
                throw new ProblemException("m2.batch.mrp_required");
            }
            return;
        }
        if (printedMrp.signum() <= 0) {
            throw new ProblemException("m2.batch.mrp_invalid");
        }
    }

    /** 22A section 6: "expiry when expiry_tracked"; an expiry is not before the manufacture. */
    static void requireExpiry(LocalDate manufactureDate, LocalDate expiryDate, boolean expiryTracked) {
        if (expiryDate == null) {
            if (expiryTracked) {
                throw new ProblemException("m2.batch.expiry_required");
            }
            return;
        }
        if (manufactureDate != null && expiryDate.isBefore(manufactureDate)) {
            throw new ProblemException("m2.batch.dates_invalid");
        }
    }

    /** As SkuGuards.reason: a code, a text, or both; at least one. */
    static String reason(String reasonCode, String reasonText) {
        String code = reasonCode == null || reasonCode.isBlank() ? null : reasonCode.strip();
        String text = reasonText == null || reasonText.isBlank() ? null : reasonText.strip();

        if (code == null && text == null) {
            throw new ProblemException("m2.batch.reason_required");
        }
        if (code == null) {
            return text;
        }
        return text == null ? code : code + ": " + text;
    }
}
