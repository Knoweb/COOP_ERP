package lk.coopfed.knoweb.m2catalogue.internal.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import org.junit.jupiter.api.Test;

/** 22A section 9, unit layer: synthetic batch naming, and the value guards of the batch handlers. */
class BatchGuardsTest {

    @Test
    void aSyntheticBatchIsNamedAfterItsDocumentLine() {
        assertThat(BatchGuards.syntheticBatchNo("GRN-000017", 3)).isEqualTo("S-GRN-000017-3");
        assertThat(BatchGuards.syntheticBatchNo(" GRN-1 ", 1)).isEqualTo("S-GRN-1-1");
        // Line 1 of GRN-11 and line 11 of GRN-1 are different batches.
        assertThat(BatchGuards.syntheticBatchNo("GRN-11", 1)).isNotEqualTo(BatchGuards.syntheticBatchNo("GRN-1", 11));
    }

    @Test
    void aSyntheticNameNeedsTheDocumentAndTheLineAndFitsTheColumn() {
        assertMessage(() -> BatchGuards.syntheticBatchNo(null, 1), "request.field.required");
        assertMessage(() -> BatchGuards.syntheticBatchNo("  ", 1), "request.field.required");
        assertMessage(() -> BatchGuards.syntheticBatchNo("GRN", null), "request.field.required");
        assertMessage(() -> BatchGuards.syntheticBatchNo("GRN", 0), "request.field.required");
        assertThat(BatchGuards.syntheticBatchNo("D".repeat(36), 1)).hasSize(40);
        assertMessage(() -> BatchGuards.syntheticBatchNo("D".repeat(37), 1), "m2.batch.batch_no_too_long");
    }

    @Test
    void theMrpAndTheExpiryFollowTheItemsFlags() {
        BatchGuards.requireMrp(null, false);
        BatchGuards.requireMrp(new BigDecimal("0.01"), true);
        assertMessage(() -> BatchGuards.requireMrp(null, true), "m2.batch.mrp_required");
        assertMessage(() -> BatchGuards.requireMrp(BigDecimal.ZERO, false), "m2.batch.mrp_invalid");

        BatchGuards.requireExpiry(null, null, false);
        BatchGuards.requireExpiry(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), true);
        assertMessage(() -> BatchGuards.requireExpiry(null, null, true), "m2.batch.expiry_required");
        assertMessage(
                () -> BatchGuards.requireExpiry(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1), false),
                "m2.batch.dates_invalid");
    }

    @Test
    void aReasonIsACodeATextOrBoth() {
        assertThat(BatchGuards.reason("MISKEYED", null)).isEqualTo("MISKEYED");
        assertThat(BatchGuards.reason(null, " typo ")).isEqualTo("typo");
        assertThat(BatchGuards.reason("MISKEYED", "typo")).isEqualTo("MISKEYED: typo");
        assertMessage(() -> BatchGuards.reason(" ", null), "m2.batch.reason_required");
    }

    private static void assertMessage(Runnable call, String messageId) {
        assertThatThrownBy(call::run).isInstanceOf(ProblemException.class).satisfies(error -> assertThat(
                        ((ProblemException) error).messageId())
                .isEqualTo(messageId));
    }
}
