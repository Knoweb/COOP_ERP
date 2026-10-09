package lk.coopfed.knoweb.m5inventory.internal.repack;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The value a repack reversal's out movement leaves without a trace in the average (wave 3, M1M2M3M5-23). */
class ReverseRepackCostResidualTest {

    private static RepackStore.ItemCost holding(String qty, String avg) {
        return new RepackStore.ItemCost(new BigDecimal(qty), new BigDecimal(avg));
    }

    @Test
    void anUntouchedOutputThatIsTheWholeHoldingLeavesNothing() {
        assertThat(ReverseRepackHandler.costResidual(
                        holding("49", "193.8776"), new BigDecimal("49"), new BigDecimal("193.8776")))
                .isEqualByComparingTo("0");
    }

    @Test
    void theReviewScenarioRecordsTheValueTheReversalCreates() {
        // 49 packs at 193.8776, 100 more received at 20 (average 77.18), the 100 sold: the entity
        // holds 49 at 77.18 (3,781.82). Taking the 49 out at 193.8776 (9,500.00) leaves nothing,
        // so 5,718.18 of value would appear with no record.
        assertThat(ReverseRepackHandler.costResidual(
                        holding("49", "77.18"), new BigDecimal("49"), new BigDecimal("193.8776")))
                .isEqualByComparingTo("-5718.18");
    }

    @Test
    void aNegativeValueTheAverageClampsAtZeroIsRecorded() {
        // 60 held at 10 (600.00); 49 out at 193.8776 (9,500.00): 11 remain, the value would be
        // -8,900.00 and the average clamps at zero.
        assertThat(ReverseRepackHandler.costResidual(
                        holding("60", "10"), new BigDecimal("49"), new BigDecimal("193.8776")))
                .isEqualByComparingTo("-8900.00");
    }

    @Test
    void aRemainderTheAverageAbsorbsIsNoResidual() {
        assertThat(ReverseRepackHandler.costResidual(
                        holding("149", "100"), new BigDecimal("49"), new BigDecimal("150")))
                .isEqualByComparingTo("0");
    }
}
