package lk.coopfed.knoweb.demo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import lk.coopfed.knoweb.demo.DemoCast.Actor;
import lk.coopfed.knoweb.demo.DemoCatalogue.Item;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m4trading.api.AcceptOrder;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.CreateDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.CreateOrder;
import lk.coopfed.knoweb.m4trading.api.DeliveryLineSummary;
import lk.coopfed.knoweb.m4trading.api.DispatchDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.IssueDeliveryNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.SubmitOrder;
import lk.coopfed.knoweb.m4trading.query.DeliveryQueries;
import lk.coopfed.knoweb.m4trading.query.DeliveryView;
import lk.coopfed.knoweb.m4trading.query.GrnQueries;
import lk.coopfed.knoweb.m4trading.query.GrnView;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.OrderView;
import lk.coopfed.knoweb.m5inventory.query.InventoryQueries;
import lk.coopfed.knoweb.m5inventory.query.LotBalance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * DEMO-02: the trading history of the demo, so that the Trading, Reports and dashboard screens do
 * not open empty. For each relationship of the demo (Federation to D101 and D102; D101 to M101 and
 * M102; D102 to M103) a number of orders, each carried through M4's own handlers as the demo user
 * whose job it is: ordered and submitted by the buyer, accepted and put on a delivery note by the
 * seller's sales, dispatched by the seller's stores, received by the buyer (every third order with
 * a line short) and invoiced by the seller's accounts. The last orders of each relationship stop
 * part-way, so every screen has something to act on live: one received but not invoiced, one in
 * transit, one accepted, one submitted.
 *
 * <p>Dated today: the handlers take the date from the one clock of the application (the order,
 * the delivery note and the invoice from M4's TradingClock) and there is no supported way to
 * back-date a document, so the history is generated as of the day of the load; faking dates in
 * the database is not done (docs/DEMO.md).
 *
 * <p>Idempotent and resumable: every order carries its key in its notes ("Demo history ..."), and
 * each run moves each order on from where it stands to its target stage, so a second run issues
 * no command.
 */
@Service
class DemoTradingHistory {

    /** How far an order of the history is carried. */
    enum Stage {
        SUBMITTED,
        ACCEPTED,
        DISPATCHED,
        RECEIVED,
        INVOICED
    }

    /** One selling relationship of the demo and who does what on each side. */
    record Lane(
            String code,
            Actor buyer,
            Actor receiver,
            UUID deliverTo,
            Actor sales,
            Actor stores,
            Actor accounts,
            int orders,
            int linesPerOrder,
            boolean federationSells) {}

    static final List<Lane> LANES = List.of(
            new Lane(
                    "FED-D101",
                    DemoCast.D101_BUYER,
                    DemoCast.D101_STORES,
                    DemoCast.D101_WAREHOUSE,
                    DemoCast.FED_SALES,
                    DemoCast.FED_STORES,
                    DemoCast.FED_ACCOUNTS,
                    12,
                    4,
                    true),
            new Lane(
                    "FED-D102",
                    DemoCast.D102_BUYER,
                    DemoCast.D102_STORES,
                    DemoCast.D102_WAREHOUSE,
                    DemoCast.FED_SALES,
                    DemoCast.FED_STORES,
                    DemoCast.FED_ACCOUNTS,
                    8,
                    4,
                    true),
            new Lane(
                    "D101-M101",
                    DemoCast.M101_BUYER,
                    DemoCast.M101_BUYER,
                    DemoCast.M101_WAREHOUSE,
                    DemoCast.D101_BUYER,
                    DemoCast.D101_STORES,
                    DemoCast.D101_ACCOUNTS,
                    8,
                    3,
                    false),
            new Lane(
                    "D101-M102",
                    DemoCast.M102_MANAGER,
                    DemoCast.M102_MANAGER,
                    DemoCast.M102_SHOP,
                    DemoCast.D101_BUYER,
                    DemoCast.D101_STORES,
                    DemoCast.D101_ACCOUNTS,
                    8,
                    3,
                    false),
            new Lane(
                    "D102-M103",
                    DemoCast.M103_MANAGER,
                    DemoCast.M103_MANAGER,
                    DemoCast.M103_SHOP,
                    DemoCast.D102_BUYER,
                    DemoCast.D102_STORES,
                    DemoCast.D102_ACCOUNTS,
                    8,
                    3,
                    false));

    /** The stages the last orders of a lane stop at, the last order first. */
    private static final List<Stage> OPEN_TAIL =
            List.of(Stage.SUBMITTED, Stage.ACCEPTED, Stage.DISPATCHED, Stage.RECEIVED);

    static final String NOTE_PREFIX = "Demo history ";

    private final Handles<CreateOrder, UUID> createOrder;
    private final Handles<SubmitOrder, String> submitOrder;
    private final Handles<AcceptOrder, UUID> acceptOrder;
    private final Handles<CreateDeliveryNote, UUID> createNote;
    private final Handles<IssueDeliveryNote, String> issueNote;
    private final Handles<DispatchDeliveryNote, Void> dispatchNote;
    private final Handles<CaptureGrn, UUID> captureGrn;
    private final Handles<ConfirmGrn, String> confirmGrn;
    private final Handles<IssueInvoice, UUID> issueInvoice;
    private final OrderQueries orders;
    private final DeliveryQueries deliveries;
    private final GrnQueries grns;
    private final InvoiceQueries invoices;
    private final InventoryQueries inventory;
    private final BatchQueries batches;
    private final Clock clock;
    private final ZoneId businessZone;

    @SuppressWarnings("java:S107") // one handler per command the history issues; a loader, not a design
    DemoTradingHistory(
            Handles<CreateOrder, UUID> createOrder,
            Handles<SubmitOrder, String> submitOrder,
            Handles<AcceptOrder, UUID> acceptOrder,
            Handles<CreateDeliveryNote, UUID> createNote,
            Handles<IssueDeliveryNote, String> issueNote,
            Handles<DispatchDeliveryNote, Void> dispatchNote,
            Handles<CaptureGrn, UUID> captureGrn,
            Handles<ConfirmGrn, String> confirmGrn,
            Handles<IssueInvoice, UUID> issueInvoice,
            OrderQueries orders,
            DeliveryQueries deliveries,
            GrnQueries grns,
            InvoiceQueries invoices,
            InventoryQueries inventory,
            BatchQueries batches,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.createOrder = createOrder;
        this.submitOrder = submitOrder;
        this.acceptOrder = acceptOrder;
        this.createNote = createNote;
        this.issueNote = issueNote;
        this.dispatchNote = dispatchNote;
        this.captureGrn = captureGrn;
        this.confirmGrn = confirmGrn;
        this.issueInvoice = issueInvoice;
        this.orders = orders;
        this.deliveries = deliveries;
        this.grns = grns;
        this.invoices = invoices;
        this.inventory = inventory;
        this.batches = batches;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    /** Loads whatever of the history is missing; {@code count} is told each command issued. */
    void load(List<Item> items, Map<String, UUID> skus, Consumer<String> count) {
        Map<UUID, Item> bySku = new HashMap<>();
        items.forEach(item -> bySku.put(skus.get(item.nameEn()), item));
        for (Lane lane : LANES) {
            Map<String, OrderView> existing = new HashMap<>();
            for (OrderView order : orders.listOrders(OrderQueries.Role.BUYER, null, scopeOf(lane.buyer()))) {
                if (order.notes() != null && order.notes().startsWith(NOTE_PREFIX)) {
                    existing.putIfAbsent(order.notes(), order);
                }
            }
            for (int no = 1; no <= lane.orders(); no++) {
                int fromEnd = lane.orders() - no;
                Stage target = fromEnd < OPEN_TAIL.size() ? OPEN_TAIL.get(fromEnd) : Stage.INVOICED;
                String key = NOTE_PREFIX + lane.code() + " week " + ((no - 1) * 4 / lane.orders() + 1) + " no. " + no;
                carry(lane, no, key, target, existing.get(key), items, skus, bySku, count);
            }
        }
    }

    @SuppressWarnings("java:S107")
    private void carry(
            Lane lane,
            int no,
            String key,
            Stage target,
            OrderView found,
            List<Item> items,
            Map<String, UUID> skus,
            Map<UUID, Item> bySku,
            Consumer<String> count) {
        ScopeContext buyer = scopeOf(lane.buyer());
        ScopeContext sales = scopeOf(lane.sales());
        LocalDate today = today();
        UUID orderId;
        String status;
        if (found == null) {
            orderId = createOrder.handle(
                    new CreateOrder(
                            lane.sales().entityId(),
                            today.plusDays(3),
                            key,
                            orderLines(lane, no, items, skus),
                            lane.deliverTo()),
                    buyer);
            count.accept("CreateOrder");
            status = "DRAFT";
        } else {
            orderId = found.orderId();
            status = found.status();
        }
        if ("DRAFT".equals(status)) {
            submitOrder.handle(new SubmitOrder(orderId), buyer);
            count.accept("SubmitOrder");
            status = "SUBMITTED";
        }
        if (target == Stage.SUBMITTED) {
            return;
        }
        if ("SUBMITTED".equals(status)) {
            acceptOrder.handle(new AcceptOrder(orderId, today.plusDays(3), List.of()), sales);
            count.accept("AcceptOrder");
        }
        if (target == Stage.ACCEPTED) {
            return;
        }

        DeliveryView note = noteOf(orderId, sales).orElse(null);
        if (note == null) {
            UUID noteId = draftNote(lane, orderId, sales);
            if (noteId == null) {
                return; // nothing allocated: the seller had none of it
            }
            count.accept("CreateDeliveryNote");
            note = deliveries.getDeliveryNote(noteId, sales).orElseThrow();
        }
        if (note.issuedAt() == null) {
            issueNote.handle(new IssueDeliveryNote(note.deliveryNoteId()), sales);
            count.accept("IssueDeliveryNote");
        }
        if (note.dispatchedAt() == null) {
            dispatchNote.handle(
                    new DispatchDeliveryNote(note.deliveryNoteId(), "NB-" + (4500 + no), null, "Sunil Perera"),
                    scopeOf(lane.stores()));
            count.accept("DispatchDeliveryNote");
        }
        if (target == Stage.DISPATCHED) {
            return;
        }

        ScopeContext receiver = scopeOf(lane.receiver());
        DeliveryView.DropView drop = note.drops().get(0);
        UUID grnId = drop.grnId();
        if (grnId == null) {
            grnId = grns.listGrns(OrderQueries.Role.BUYER, receiver).stream()
                    .filter(grn -> drop.dropId().equals(grn.dropId()))
                    .map(GrnView::grnId)
                    .findFirst()
                    .orElse(null);
        }
        if (grnId == null) {
            grnId = captureGrn.handle(
                    new CaptureGrn(drop.dropId(), lane.deliverTo(), null, grnLines(lane, no, drop, bySku)), receiver);
            count.accept("CaptureGrn");
        }
        GrnView grn = grns.getGrn(grnId, receiver).orElseThrow();
        if (grn.confirmedAt() == null) {
            confirmGrn.handle(new ConfirmGrn(grnId), receiver);
            count.accept("ConfirmGrn");
        }
        if (target == Stage.RECEIVED) {
            return;
        }

        ScopeContext accounts = scopeOf(lane.accounts());
        UUID received = grnId;
        boolean invoiced = invoices.listInvoices(OrderQueries.Role.SELLER, accounts).stream()
                .anyMatch(invoice -> invoice.grnIds().contains(received));
        if (!invoiced) {
            issueInvoice.handle(new IssueInvoice(List.of(grnId)), accounts);
            count.accept("IssueInvoice");
        }
    }

    /** A few items of the catalogue, chosen by the order's number, in modest quantities. */
    private static List<CreateOrder.Line> orderLines(Lane lane, int no, List<Item> items, Map<String, UUID> skus) {
        List<CreateOrder.Line> lines = new ArrayList<>();
        int offset = Math.abs(lane.code().hashCode()) % items.size();
        for (int j = 0; j < lane.linesPerOrder(); j++) {
            Item item = items.get((offset + no * 7 + j * 11) % items.size());
            BigDecimal stock = lane.federationSells() ? item.federationQty() : item.distributorQty();
            BigDecimal qty = stock.divide(BigDecimal.valueOf(lane.federationSells() ? 50 : 40), 0, RoundingMode.DOWN)
                    .add(BigDecimal.valueOf((no + j) % 3));
            if (qty.signum() <= 0) {
                qty = BigDecimal.ONE;
            }
            lines.add(new CreateOrder.Line(skus.get(item.nameEn()), item.baseUom(), qty));
        }
        return lines;
    }

    /** A note of every allocated line in full from the seller's stores, each from its first lot in FEFO order. */
    private UUID draftNote(Lane lane, UUID orderId, ScopeContext sales) {
        OrderView order = orders.getOrder(orderId, sales).orElseThrow();
        UUID from = lane.stores().locationId();
        ScopeContext stores = scopeOf(lane.stores());
        List<CreateDeliveryNote.Line> lines = new ArrayList<>();
        for (OrderView.OrderLineView line : order.lines()) {
            BigDecimal qty = line.allocatedQty() == null ? BigDecimal.ZERO : line.allocatedQty();
            if (line.fulfilledQty() != null) {
                qty = qty.subtract(line.fulfilledQty());
            }
            if (qty.signum() <= 0) {
                continue;
            }
            BigDecimal wanted = qty;
            UUID batch = inventory.pickBatches(from, line.skuId(), stores).stream()
                    .filter(lot -> lot.qtyOnHand().compareTo(wanted) >= 0)
                    .map(LotBalance::batchId)
                    .findFirst()
                    .orElse(null);
            lines.add(new CreateDeliveryNote.Line(line.lineId(), qty, batch));
        }
        if (lines.isEmpty()) {
            return null;
        }
        return createNote.handle(
                new CreateDeliveryNote(
                        null,
                        null,
                        null,
                        List.of(new CreateDeliveryNote.Drop(lane.deliverTo(), order.buyerEntityId(), lines)),
                        from),
                sales);
    }

    /**
     * The count as the receiver keys it: what was sent, the batch, expiry and MRP of the seller's
     * batch; every third order has its last line short by a tenth (at least one).
     */
    private List<CaptureGrn.Line> grnLines(Lane lane, int no, DeliveryView.DropView drop, Map<UUID, Item> bySku) {
        ScopeContext seller = scopeOf(lane.stores());
        List<CaptureGrn.Line> lines = new ArrayList<>();
        List<DeliveryLineSummary> sent = drop.lines();
        LocalDate today = today();
        for (int i = 0; i < sent.size(); i++) {
            DeliveryLineSummary line = sent.get(i);
            Item item = bySku.get(line.skuId());
            BigDecimal received = line.dispatchedQty();
            if (no % 3 == 1 && i == sent.size() - 1 && received.compareTo(BigDecimal.ONE) > 0) {
                BigDecimal tenth = received.divide(BigDecimal.TEN, 0, RoundingMode.DOWN);
                received = received.subtract(tenth.signum() > 0 ? tenth : BigDecimal.ONE);
            }
            Optional<BatchView> batch =
                    line.batchId() == null ? Optional.empty() : batches.getBatch(line.batchId(), seller);
            boolean batchTracked = item != null && item.batchTracked();
            boolean expiryTracked = item != null && item.expiryTracked();
            boolean mrp = item != null && item.hasPrintedMrp();
            String batchNo = batch.map(BatchView::batchNo)
                    .orElse(batchTracked ? "DEMO-" + today.getYear() + "-" + item.lineNo() : null);
            LocalDate expiry = batch.map(BatchView::expiryDate)
                    .orElse(expiryTracked ? today.plusDays(item.shelfLifeDays()) : null);
            BigDecimal printedMrp = batch.map(BatchView::printedMrp).orElse(mrp ? item.printedMrp() : null);
            lines.add(new CaptureGrn.Line(
                    line.skuId(),
                    line.uomCode(),
                    received,
                    BigDecimal.ZERO,
                    batchNo,
                    null,
                    expiryTracked ? expiry : null,
                    mrp ? printedMrp : null));
        }
        return lines;
    }

    private Optional<DeliveryView> noteOf(UUID orderId, ScopeContext sales) {
        return deliveries.listDeliveryNotes(OrderQueries.Role.SELLER, sales).stream()
                .filter(note ->
                        note.drops().stream().anyMatch(drop -> drop.orderIds().contains(orderId)))
                .findFirst();
    }

    private ScopeContext scopeOf(Actor actor) {
        Scope scope = new Scope(actor.entityId(), actor.locationId());
        return new ScopeContext(
                actor.userId(),
                null,
                actor.entityId(),
                List.of(scope),
                scope,
                PolicyClass.OWN,
                Set.of(),
                clock.instant(),
                Locale.ENGLISH,
                null);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), businessZone);
    }
}
