package lk.coopfed.knoweb.m4trading.internal.grn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads.GrnLine;

/**
 * DiscrepancyDetector (24A section 6.1, step 4): a GRN line of a delivery varies when what was
 * received differs from what the drop expected, or when any of it arrived damaged. The kind of the
 * discrepancy is SHORT, OVER or DAMAGED when all varying lines agree, MIXED otherwise.
 */
public final class DiscrepancyDetector {

    private DiscrepancyDetector() {}

    public static Optional<Finding> detect(List<GrnLine> lines) {
        List<Variance> varying = new ArrayList<>();
        boolean shortage = false;
        boolean over = false;
        boolean damage = false;
        for (GrnLine line : lines) {
            if (line.expectedQty() == null) {
                continue; // a local supply expects nothing
            }
            BigDecimal variance = line.receivedQty().subtract(line.expectedQty());
            boolean damaged = line.damagedQty() != null && line.damagedQty().signum() > 0;
            if (variance.signum() == 0 && !damaged) {
                continue;
            }
            shortage |= variance.signum() < 0;
            over |= variance.signum() > 0;
            damage |= damaged;
            varying.add(new Variance(line, variance));
        }
        if (varying.isEmpty()) {
            return Optional.empty();
        }
        int kinds = (shortage ? 1 : 0) + (over ? 1 : 0) + (damage ? 1 : 0);
        String kind = kinds > 1 ? "MIXED" : shortage ? "SHORT" : over ? "OVER" : "DAMAGED";
        return Optional.of(new Finding(kind, List.copyOf(varying)));
    }

    public record Finding(String kind, List<Variance> lines) {}

    public record Variance(GrnLine line, BigDecimal varianceQty) {}
}
