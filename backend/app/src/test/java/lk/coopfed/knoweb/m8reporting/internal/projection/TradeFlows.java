package lk.coopfed.knoweb.m8reporting.internal.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteDispatched;
import lk.coopfed.knoweb.m4trading.api.GrnConfirmed;
import lk.coopfed.knoweb.m4trading.api.GrnLineConfirmed;
import lk.coopfed.knoweb.m4trading.api.InvoiceIssued;
import lk.coopfed.knoweb.m4trading.api.OrderAccepted;
import lk.coopfed.knoweb.m4trading.api.OrderCancelled;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.OrderRejected;
import lk.coopfed.knoweb.m4trading.api.OrderSubmitted;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness.Delivery;

/** Random trading flows with M4's event records, as the owning party of each would publish them. */
public final class TradeFlows {

    private TradeFlows() {}

    /**
     * One order from submission to where {@code depth} stops it: 0 submitted only, 1 rejected
     * or cancelled, 2 accepted and dispatched (in transit), 3 received and invoiced.
     */
    public static List<Delivery> flow(
            ProjectionHarness harness, UUID seller, UUID buyer, UUID[] skus, Random random, Instant t, int depth) {
        List<Delivery> events = new ArrayList<>();
        UUID order = Ids.next();
        UUID relationship = Ids.next();
        List<OrderLineSummary> submitted = new ArrayList<>();
        List<OrderLineSummary> accepted = new ArrayList<>();
        List<GrnLineConfirmed> received = new ArrayList<>();
        int lines = 1 + random.nextInt(3);
        for (int n = 1; n <= lines; n++) {
            UUID line = Ids.next();
            UUID sku = skus[random.nextInt(skus.length)];
            BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(50));
            BigDecimal price = new BigDecimal(50 + random.nextInt(200) + ".2500");
            submitted.add(new OrderLineSummary(line, n, sku, "EA", qty, null, null));
            accepted.add(new OrderLineSummary(line, n, sku, "EA", qty, qty, price));
            received.add(new GrnLineConfirmed(
                    Ids.next(),
                    n,
                    sku,
                    Ids.next(),
                    "B" + n,
                    LocalDate.of(2027, 6, 30),
                    new BigDecimal("300.00"),
                    "EA",
                    qty,
                    qty,
                    BigDecimal.ZERO,
                    price));
        }
        events.add(harness.event(
                OrderSubmitted.TYPE,
                buyer,
                t,
                new OrderSubmitted(order, "ORD-" + order, relationship, buyer, seller, null, submitted)));
        if (depth == 0) {
            return events;
        }
        if (depth == 1) {
            events.add(
                    random.nextBoolean()
                            ? harness.event(
                                    OrderRejected.TYPE,
                                    seller,
                                    t.plusSeconds(60),
                                    new OrderRejected(order, relationship, buyer, seller, "NO_STOCK"))
                            : harness.event(
                                    OrderCancelled.TYPE,
                                    buyer,
                                    t.plusSeconds(60),
                                    new OrderCancelled(order, relationship, buyer, seller, buyer, "CHANGED")));
            return events;
        }
        events.add(harness.event(
                OrderAccepted.TYPE,
                seller,
                t.plusSeconds(60),
                new OrderAccepted(order, relationship, buyer, seller, Ids.next(), null, null, accepted)));
        UUID note = Ids.next();
        events.add(harness.event(
                DeliveryNoteDispatched.TYPE,
                seller,
                t.plusSeconds(120),
                new DeliveryNoteDispatched(note, "DN-" + note, seller, buyer, t.plusSeconds(120), null, null)));
        if (depth == 2) {
            return events;
        }
        UUID grn = Ids.next();
        events.add(harness.event(
                GrnConfirmed.TYPE,
                buyer,
                t.plusSeconds(180),
                new GrnConfirmed(
                        grn,
                        "GRN-" + grn,
                        buyer,
                        Ids.next(),
                        seller,
                        relationship,
                        Ids.next(),
                        note,
                        null,
                        t.plusSeconds(180),
                        false,
                        received)));
        BigDecimal net = BigDecimal.ZERO;
        for (GrnLineConfirmed line : received) {
            net = net.add(line.receivedQty().multiply(line.unitCost()));
        }
        net = net.setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal tax = net.multiply(new BigDecimal("0.18")).setScale(2, java.math.RoundingMode.HALF_UP);
        LocalDate taxPoint = t.plusSeconds(240).atZone(ZoneOffset.UTC).toLocalDate();
        events.add(harness.event(
                InvoiceIssued.TYPE,
                seller,
                t.plusSeconds(240),
                new InvoiceIssued(
                        Ids.next(),
                        "INV-" + grn,
                        relationship,
                        seller,
                        buyer,
                        null,
                        null,
                        List.of(grn),
                        taxPoint,
                        taxPoint.plusDays(30),
                        net,
                        tax,
                        net.add(tax),
                        "hash")));
        return events;
    }
}
