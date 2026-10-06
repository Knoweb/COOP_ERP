package lk.coopfed.knoweb.m5inventory.internal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
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
        assertThat(CostService.costAtMovement(row, MovementType.SALE, new BigDecimal("-15"), null))
                .isEqualByComparingTo("115");
        row = CostService.apply(row, MovementType.SALE, new BigDecimal("-15"), null);
        assertThat(row.avgCost()).isEqualByComparingTo("115");
        assertThat(row.qtyOnHand()).isEqualByComparingTo("25");

        // A transfer in carries its own cost (its transfer out's) and re-averages (wave 2, M5-06):
        // (25 × 115 + 5 × 99) / 30 = 3,370 / 30.
        assertThat(CostService.costAtMovement(row, MovementType.TRANSFER_IN, new BigDecimal("5"), new BigDecimal("99")))
                .isEqualByComparingTo("99");
        row = CostService.apply(row, MovementType.TRANSFER_IN, new BigDecimal("5"), new BigDecimal("99"));
        assertThat(row.avgCost()).isEqualByComparingTo("112.3333");
        assertThat(row.qtyOnHand()).isEqualByComparingTo("30");
    }

    @Test
    void aTransferPairConservesValueWhenTheWholeHoldingIsInTransitAndAnIntakeLandsMeanwhile() {
        // The finding's scenario (M5-06): 100 at 10.0000, all of it sent to a shop; a GRN of 50 at
        // 20.0000 lands before the shop receives. The true average is (1,000 + 1,000) / 150.
        CostRow row = CostService.apply(CostRow.empty(), MovementType.RECEIPT, new BigDecimal("100"), BigDecimal.TEN);
        BigDecimal leftAt = CostService.costAtMovement(row, MovementType.TRANSFER_OUT, new BigDecimal("-100"), null);
        row = CostService.apply(row, MovementType.TRANSFER_OUT, new BigDecimal("-100"), null);
        row = CostService.apply(row, MovementType.RECEIPT, new BigDecimal("50"), new BigDecimal("20"));
        assertThat(row.avgCost()).isEqualByComparingTo("20");

        row = CostService.apply(row, MovementType.TRANSFER_IN, new BigDecimal("100"), leftAt);

        assertThat(row.qtyOnHand()).isEqualByComparingTo("150");
        assertThat(row.avgCost()).isEqualByComparingTo("13.3333");
    }

    @Test
    void aTransferPairConservesValueWhenPartOfTheHoldingIsInTransit() {
        // The fix review's wider case: 200 at 10 (two warehouses), 100 in transit, a GRN of 100 at
        // 20. Today the average stayed 15; the pool that kept the transit stock says 13.3333.
        CostRow row = CostService.apply(CostRow.empty(), MovementType.RECEIPT, new BigDecimal("200"), BigDecimal.TEN);
        BigDecimal leftAt = CostService.costAtMovement(row, MovementType.TRANSFER_OUT, new BigDecimal("-100"), null);
        row = CostService.apply(row, MovementType.TRANSFER_OUT, new BigDecimal("-100"), null);
        row = CostService.apply(row, MovementType.RECEIPT, new BigDecimal("100"), new BigDecimal("20"));
        assertThat(row.avgCost()).isEqualByComparingTo("15");

        row = CostService.apply(row, MovementType.TRANSFER_IN, new BigDecimal("100"), leftAt);

        assertThat(row.qtyOnHand()).isEqualByComparingTo("300");
        assertThat(row.avgCost()).isEqualByComparingTo("13.3333");
    }

    @Test
    void anOutAtAGivenCostRemovesExactlyThatValue() {
        // 100 at 12 = 1,200; 10 leave at 30 = 300; 900 / 90 = 10.
        CostRow row =
                CostService.apply(CostRow.empty(), MovementType.RECEIPT, new BigDecimal("100"), new BigDecimal("12"));
        assertThat(CostService.costAtMovement(
                        row, MovementType.REPACK_CONSUME, new BigDecimal("-10"), new BigDecimal("30")))
                .isEqualByComparingTo("30");
        row = CostService.apply(row, MovementType.REPACK_CONSUME, new BigDecimal("-10"), new BigDecimal("30"));
        assertThat(row.qtyOnHand()).isEqualByComparingTo("90");
        assertThat(row.avgCost()).isEqualByComparingTo("10");

        // Nothing left: the last average stays.
        row = CostService.apply(row, MovementType.REPACK_CONSUME, new BigDecimal("-90"), new BigDecimal("15"));
        assertThat(row.qtyOnHand()).isEqualByComparingTo("0");
        assertThat(row.avgCost()).isEqualByComparingTo("10");

        // More value out than the pool held: clamped at zero, never negative.
        row = CostService.apply(CostRow.empty(), MovementType.RECEIPT, new BigDecimal("10"), BigDecimal.ONE);
        row = CostService.apply(row, MovementType.REPACK_CONSUME, new BigDecimal("-5"), BigDecimal.TEN);
        assertThat(row.qtyOnHand()).isEqualByComparingTo("5");
        assertThat(row.avgCost()).isEqualByComparingTo("0");

        // An out at the average is the same rule and leaves the average where it was.
        row = CostService.apply(CostRow.empty(), MovementType.RECEIPT, new BigDecimal("7"), new BigDecimal("3.3333"));
        CostRow atAverage = CostService.apply(row, MovementType.SALE, new BigDecimal("-2"), row.avgCost());
        CostRow plain = CostService.apply(row, MovementType.SALE, new BigDecimal("-2"), null);
        assertThat(atAverage).isEqualTo(plain);
    }

    @Test
    void aRepackReversalAtTheRepacksCostPutsTheOutputItemsAverageBack() {
        // The output item already held 20 packs at 150 (another recipe). The repack adds 49 at
        // 193.8776; the reversal takes them out at that cost, not at the mixed average (M5-17).
        CostRow row =
                CostService.apply(CostRow.empty(), MovementType.RECEIPT, new BigDecimal("20"), new BigDecimal("150"));
        BigDecimal outputCost = new BigDecimal("193.8776");
        row = CostService.apply(row, MovementType.REPACK_PRODUCE, new BigDecimal("49"), outputCost);
        // (20 × 150 + 49 × 193.8776) / 69 = 12,500.0024 / 69
        assertThat(row.avgCost()).isEqualByComparingTo("181.1595");

        row = CostService.apply(row, MovementType.REPACK_CONSUME, new BigDecimal("-49"), outputCost);

        assertThat(row.qtyOnHand()).isEqualByComparingTo("20");
        // Within the rounding of the average in between (four decimals, spread over 20 packs).
        assertThat(row.avgCost()).isBetween(new BigDecimal("149.9998"), new BigDecimal("150.0002"));
    }

    @Test
    void aSaleReversalComesBackAtTheSalesCostAndReaverages() {
        // wave 2, D10: the void returns the units at the original SALE's unit_cost_at_movement.
        CostRow row =
                CostService.apply(CostRow.empty(), MovementType.RECEIPT, new BigDecimal("10"), new BigDecimal("100"));
        BigDecimal soldAt = CostService.costAtMovement(row, MovementType.SALE, new BigDecimal("-4"), null);
        row = CostService.apply(row, MovementType.SALE, new BigDecimal("-4"), null);
        row = CostService.apply(row, MovementType.RECEIPT, new BigDecimal("6"), new BigDecimal("120"));
        assertThat(row.avgCost()).isEqualByComparingTo("110");

        row = CostService.apply(row, MovementType.SALE_REVERSAL, new BigDecimal("4"), soldAt);

        // (12 × 110 + 4 × 100) / 16 = 1,720 / 16: the pool the void never happened in.
        assertThat(row.qtyOnHand()).isEqualByComparingTo("16");
        assertThat(row.avgCost()).isEqualByComparingTo("107.5");
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
            // A transfer in is one half of a pair inside the entity: it brings back what a
            // TRANSFER_OUT took, at the cost that out left with (wave 2, M5-06).
            Deque<BigDecimal[]> inTransit = new ArrayDeque<>();

            for (int s = 0; s < steps; s++) {
                MovementType type = types[random.nextInt(types.length)];
                BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(50_000), 3);
                BigDecimal cost = type.carriesItsOwnCost() ? BigDecimal.valueOf(random.nextInt(10_000_000), 4) : null;
                if (type == MovementType.TRANSFER_IN) {
                    if (inTransit.isEmpty()) {
                        continue;
                    }
                    BigDecimal[] pair = inTransit.poll();
                    qty = pair[0];
                    cost = pair[1];
                }
                BigDecimal delta =
                        switch (type.direction()) {
                            case IN -> qty;
                            case OUT -> qty.negate();
                            case EITHER -> random.nextBoolean() ? qty : qty.negate();
                        };
                if (type == MovementType.TRANSFER_OUT) {
                    inTransit.add(new BigDecimal[] {qty, CostService.costAtMovement(row, type, delta, null)});
                }

                // Every unit that comes in comes at a cost: its own for an intake, the average
                // of the moment otherwise (a count gain). The average can only lie between those
                // costs.
                if (delta.signum() > 0) {
                    BigDecimal in = CostService.costAtMovement(row, type, delta, cost);
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
    void anOutAtAGivenCostConservesValueOrStopsAtZero() {
        Random random = new Random(20261006L);
        BigDecimal halfUnit = new BigDecimal("0.00005");
        for (int c = 0; c < CASES; c++) {
            CostRow row = CostService.apply(
                    CostRow.empty(),
                    MovementType.RECEIPT,
                    BigDecimal.valueOf(2 + random.nextInt(50_000), 3),
                    BigDecimal.valueOf(random.nextInt(10_000_000), 4));
            BigDecimal out = BigDecimal.valueOf(
                    1 + random.nextInt(row.qtyOnHand().movePointRight(3).intValue() - 1), 3);
            BigDecimal cost = BigDecimal.valueOf(random.nextInt(10_000_000), 4);

            CostRow after = CostService.apply(row, MovementType.REPACK_CONSUME, out.negate(), cost);

            BigDecimal valueLeft = row.qtyOnHand().multiply(row.avgCost()).subtract(out.multiply(cost));
            assertThat(after.qtyOnHand())
                    .as("case %d", c)
                    .isEqualByComparingTo(row.qtyOnHand().subtract(out));
            assertThat(after.avgCost().signum())
                    .as("case %d: never negative", c)
                    .isGreaterThanOrEqualTo(0);
            if (valueLeft.signum() >= 0) {
                assertThat(after.qtyOnHand()
                                .multiply(after.avgCost())
                                .subtract(valueLeft)
                                .abs())
                        .as("case %d: the value left is the value before less the value taken out", c)
                        .isLessThanOrEqualTo(after.qtyOnHand().multiply(halfUnit));
            } else {
                assertThat(after.avgCost()).as("case %d: clamped", c).isEqualByComparingTo("0");
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
