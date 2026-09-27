package lk.coopfed.knoweb.m5inventory.internal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Random;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.internal.ledger.CostService.CostRow;
import org.junit.jupiter.api.Test;

/**
 * The weighted average (25A section 9: "CostService arithmetic through doc 13 scenario 3 (9,500/49
 * = 193.88) and mixed receipts"; "avg cost ... never negative"). The property cases run on a fixed
 * seed so a failure is reproducible; jqwik is not in the build, so a loop of 5,000 generated
 * sequences stands in for it (the case count of 25A section 9).
 */
class CostServiceTest {

    private static final int CASES = 5_000;

    @Test
    void docThirteenScenarioThreeTheRepackOutputCostsTheConsumedCostOverTheOutput() {
        // 50 kg consumed at 190.00 = 9,500.00; 49 packs produced at 9,500/49.
        BigDecimal outputCost = new BigDecimal("9500").divide(new BigDecimal("49"), 4, CostService.ROUNDING);
        CostRow row =
                CostService.apply(CostRow.empty(), MovementType.REPACK_PRODUCE, new BigDecimal("49.000"), outputCost);

        assertThat(row.avgCost()).isEqualByComparingTo("193.8776");
        assertThat(row.avgCost().setScale(2, CostService.ROUNDING)).isEqualByComparingTo("193.88");
        assertThat(row.qtyOnHand()).isEqualByComparingTo("49");
    }

    @Test
    void mixedReceiptsAverageByQuantity() {
        CostRow row = CostRow.empty();
        row = CostService.apply(row, MovementType.RECEIPT, new BigDecimal("10"), new BigDecimal("100"));
        row = CostService.apply(row, MovementType.RECEIPT, new BigDecimal("30"), new BigDecimal("120"));
        // (10 × 100 + 30 × 120) / 40 = 4,600 / 40
        assertThat(row.avgCost()).isEqualByComparingTo("115.0000");
        assertThat(row.qtyOnHand()).isEqualByComparingTo("40");

        // An issue carries the average and changes the quantity only.
        assertThat(CostService.costAtMovement(row, MovementType.SALE, null)).isEqualByComparingTo("115");
        row = CostService.apply(row, MovementType.SALE, new BigDecimal("-15"), null);
        assertThat(row.avgCost()).isEqualByComparingTo("115");
        assertThat(row.qtyOnHand()).isEqualByComparingTo("25");

        // A transfer in carries the source lot's cost and leaves the entity average alone.
        assertThat(CostService.costAtMovement(row, MovementType.TRANSFER_IN, new BigDecimal("99")))
                .isEqualByComparingTo("99");
        row = CostService.apply(row, MovementType.TRANSFER_IN, new BigDecimal("5"), new BigDecimal("99"));
        assertThat(row.avgCost()).isEqualByComparingTo("115");
        assertThat(row.qtyOnHand()).isEqualByComparingTo("30");
    }

    @Test
    void anIntakeAfterAnOversellTakesItsOwnCost() {
        CostRow row =
                CostService.apply(CostRow.empty(), MovementType.RECEIPT, new BigDecimal("2"), new BigDecimal("50"));
        row = CostService.apply(row, MovementType.SALE, new BigDecimal("-5"), null);
        assertThat(row.qtyOnHand()).isEqualByComparingTo("-3");
        assertThat(row.avgCost()).isEqualByComparingTo("50");

        row = CostService.apply(row, MovementType.RECEIPT, new BigDecimal("10"), new BigDecimal("60"));
        assertThat(row.qtyOnHand()).isEqualByComparingTo("7");
        assertThat(row.avgCost()).isEqualByComparingTo("60");
    }

    @Test
    void theAverageIsNeverNegativeStaysWithinTheIntakeCostsAndTheQuantityIsTheSumOfTheMovements() {
        Random random = new Random(20260927L);
        MovementType[] types = MovementType.values();

        for (int c = 0; c < CASES; c++) {
            CostRow row = CostRow.empty();
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal lowest = null;
            BigDecimal highest = null;
            int steps = 1 + random.nextInt(40);

            for (int s = 0; s < steps; s++) {
                MovementType type = types[random.nextInt(types.length)];
                if (type == MovementType.TRANSFER_IN) {
                    // A transfer in is one half of a pair inside the entity (its TRANSFER_OUT took
                    // the same quantity out at the average): alone it is not a sequence the
                    // ledger can see. The pair is covered by mixedReceiptsAverageByQuantity.
                    continue;
                }
                BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(50_000), 3);
                BigDecimal delta =
                        switch (type.direction()) {
                            case IN -> qty;
                            case OUT -> qty.negate();
                            case EITHER -> random.nextBoolean() ? qty : qty.negate();
                        };
                BigDecimal cost = type.carriesItsOwnCost() ? BigDecimal.valueOf(random.nextInt(10_000_000), 4) : null;

                // Every unit that comes in comes at a cost: its own for an intake, the average
                // of the moment otherwise (a sale reversal, a count gain). The average can only
                // lie between those costs.
                if (delta.signum() > 0) {
                    BigDecimal in = CostService.costAtMovement(row, type, cost);
                    lowest = lowest == null || in.compareTo(lowest) < 0 ? in : lowest;
                    highest = highest == null || in.compareTo(highest) > 0 ? in : highest;
                }
                row = CostService.apply(row, type, delta, cost);
                sum = sum.add(delta);

                assertThat(row.avgCost().signum())
                        .as("case %d step %d: the average is never negative", c, s)
                        .isGreaterThanOrEqualTo(0);
                assertThat(row.qtyOnHand())
                        .as("case %d step %d: the quantity is the sum of the movements", c, s)
                        .isEqualByComparingTo(sum);
                if (lowest != null) {
                    // A weighted average lies between the costs averaged (one unit of rounding either way).
                    BigDecimal unit = new BigDecimal("0.0001");
                    assertThat(row.avgCost())
                            .as("case %d step %d: the average stays within the intake costs", c, s)
                            .isBetween(lowest.subtract(unit), highest.add(unit));
                }
            }
        }
    }

    @Test
    void replayingTheSameMovementsGivesTheSameRow() {
        Random random = new Random(7L);
        for (int c = 0; c < CASES; c++) {
            long seed = random.nextLong();
            assertThat(run(seed)).as("case %d", c).isEqualTo(run(seed));
        }
    }

    private static CostRow run(long seed) {
        Random random = new Random(seed);
        CostRow row = CostRow.empty();
        for (int s = 0; s < 20; s++) {
            boolean receipt = random.nextBoolean();
            BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(10_000), 3);
            row = receipt
                    ? CostService.apply(
                            row, MovementType.RECEIPT, qty, BigDecimal.valueOf(random.nextInt(1_000_000), 4))
                    : CostService.apply(row, MovementType.SALE, qty.negate(), null);
        }
        return row;
    }
}
