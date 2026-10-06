package lk.coopfed.knoweb.m8reporting.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.DeliveryNoteDispatched;
import lk.coopfed.knoweb.m4trading.api.GrnConfirmed;
import lk.coopfed.knoweb.m4trading.api.GrnLineConfirmed;
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputeResolved;
import lk.coopfed.knoweb.m4trading.api.InvoiceDisputed;
import lk.coopfed.knoweb.m4trading.api.InvoiceIssued;
import lk.coopfed.knoweb.m4trading.api.OrderAccepted;
import lk.coopfed.knoweb.m4trading.api.OrderCancelled;
import lk.coopfed.knoweb.m4trading.api.OrderLineSummary;
import lk.coopfed.knoweb.m4trading.api.OrderRejected;
import lk.coopfed.knoweb.m4trading.api.OrderSubmitted;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness.Delivery;
import lk.coopfed.knoweb.m8reporting.query.Dashboard;
import lk.coopfed.knoweb.m8reporting.query.ReportParameters;
import lk.coopfed.knoweb.m8reporting.query.ReportingQueries;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The trading projections (M8-04 in part) against the payloads of M4's event records: rebuild
 * equivalence over random trading flows first (28A section 9), then what one flow leaves.
 */
class TradeProjectionPostgresIntegrationTest extends PostgresIntegrationTest {

    static final List<String> TABLES = List.of(
            "reporting.projection_state",
            "reporting.trade_document_event",
            "reporting.trade_line_fact",
            "reporting.trade_document_link",
            "reporting.trade_settlement_fact",
            "reporting.exposure_warning_event");

    @Autowired
    TradeProjection projection;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ReportingQueries queries;

    private ProjectionHarness harness;
    private JdbcTemplate admin;

    @BeforeEach
    void arrange() {
        admin = superuserJdbc();
        harness = new ProjectionHarness(mapper, admin);
        harness.empty(TABLES);
        kernel.reset();
    }

    @AfterEach
    void clean() {
        harness.empty(TABLES);
    }

    @Test
    void aRebuildFromZeroGivesTheRowsTheLiveConsumerGaveForRandomFlows() {
        for (long seed = 1; seed <= 5; seed++) {
            Random random = new Random(seed);
            List<Delivery> events = new ArrayList<>();
            UUID seller = Ids.next();
            UUID[] buyers = {Ids.next(), Ids.next(), Ids.next()};
            UUID[] skus = {Ids.next(), Ids.next(), Ids.next(), Ids.next()};
            Instant t = Instant.parse("2026-09-20T03:00:00Z");
            for (int i = 0; i < 12; i++) {
                t = t.plusSeconds(3_600 + random.nextInt(40_000));
                events.addAll(TradeFlows.flow(
                        harness, seller, buyers[random.nextInt(3)], skus, random, t, random.nextInt(5)));
            }

            ProjectionHarness.Result result = harness.liveThenRebuild(events, projection::on, TABLES, seed);

            assertThat(result.live().get("reporting.trade_document_event")).isNotEmpty();
            assertThat(result.rebuilt()).as("seed %d", seed).isEqualTo(result.live());
        }
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void aWholeFlowLeavesOneRowPerEventOnTheSideOfItsOwnerAndTheVolumeAtTheGrn() {
        UUID seller = Ids.next();
        UUID buyer = Ids.next();
        UUID sku = Ids.next();
        UUID order = Ids.next();
        UUID orderLine = Ids.next();
        UUID note = Ids.next();
        UUID grn = Ids.next();
        UUID invoice = Ids.next();
        UUID relationship = Ids.next();
        // 23:30 in Colombo on the 26th is 18:00 UTC: the business date is the 26th.
        Instant t = Instant.parse("2026-09-26T18:00:00Z");

        OrderLineSummary submitted = new OrderLineSummary(orderLine, 1, sku, "EA", new BigDecimal("10"), null, null);
        OrderLineSummary accepted = new OrderLineSummary(
                orderLine, 1, sku, "EA", new BigDecimal("10"), new BigDecimal("8"), new BigDecimal("95.5000"));
        List<Delivery> flow = List.of(
                harness.event(
                        OrderSubmitted.TYPE,
                        buyer,
                        t,
                        new OrderSubmitted(order, "D101-ORD-1", relationship, buyer, seller, null, List.of(submitted))),
                harness.event(
                        OrderAccepted.TYPE,
                        seller,
                        t.plusSeconds(60),
                        new OrderAccepted(
                                order, relationship, buyer, seller, Ids.next(), null, null, List.of(accepted))),
                harness.event(
                        DeliveryNoteDispatched.TYPE,
                        seller,
                        t.plusSeconds(120),
                        new DeliveryNoteDispatched(
                                note, "FED-DN-1", seller, buyer, t.plusSeconds(120), "WP-1234", null)),
                harness.event(
                        GrnConfirmed.TYPE,
                        buyer,
                        t.plusSeconds(180),
                        new GrnConfirmed(
                                grn,
                                "D101-GRN-1",
                                buyer,
                                Ids.next(),
                                seller,
                                relationship,
                                Ids.next(),
                                note,
                                null,
                                t.plusSeconds(180),
                                false,
                                List.of(new GrnLineConfirmed(
                                        Ids.next(),
                                        1,
                                        sku,
                                        Ids.next(),
                                        "B1",
                                        LocalDate.of(2027, 1, 1),
                                        new BigDecimal("120.00"),
                                        "EA",
                                        new BigDecimal("8"),
                                        new BigDecimal("7"),
                                        new BigDecimal("1"),
                                        new BigDecimal("95.5000"))))),
                harness.event(
                        InvoiceIssued.TYPE,
                        seller,
                        t.plusSeconds(240),
                        new InvoiceIssued(
                                invoice,
                                "FED-INV-1",
                                relationship,
                                seller,
                                buyer,
                                "V1",
                                "V2",
                                List.of(grn),
                                LocalDate.of(2026, 9, 27),
                                LocalDate.of(2026, 10, 27),
                                new BigDecimal("668.50"),
                                new BigDecimal("120.33"),
                                new BigDecimal("788.83"),
                                "hash")));
        for (Delivery event : flow) {
            harness.deliver(event, projection::on);
        }

        List<Map<String, Object>> rows =
                admin.queryForList("select * from reporting.trade_document_event order by occurred_at");
        assertThat(rows)
                .extracting(r -> r.get("event_kind"))
                .containsExactly("SUBMITTED", "ACCEPTED", "DISPATCHED", "CONFIRMED", "ISSUED");
        assertThat(rows)
                .extracting(r -> r.get("owner_entity_id"))
                .containsExactly(buyer, seller, seller, buyer, seller);
        assertThat(rows)
                .extracting(r -> r.get("counterparty_entity_id"))
                .containsExactly(seller, buyer, buyer, seller, buyer);
        assertThat(rows.get(0).get("business_date").toString()).isEqualTo("2026-09-26");
        assertThat(rows.get(0).get("doc_number")).isEqualTo("D101-ORD-1");
        assertThat((BigDecimal) rows.get(1).get("net")).isEqualByComparingTo("764.00");
        assertThat(rows.get(3).get("reference_document_id")).isEqualTo(note);
        assertThat((BigDecimal) rows.get(3).get("net")).isEqualByComparingTo("668.50");
        assertThat(rows.get(4).get("business_date").toString()).isEqualTo("2026-09-27");
        assertThat((BigDecimal) rows.get(4).get("gross")).isEqualByComparingTo("788.83");

        List<Map<String, Object>> lines =
                admin.queryForList("select * from reporting.trade_line_fact order by measure");
        assertThat(lines).extracting(r -> r.get("measure")).containsExactly("ACCEPTED", "RECEIVED");
        assertThat((BigDecimal) lines.get(0).get("qty")).isEqualByComparingTo("8");
        assertThat((BigDecimal) lines.get(1).get("qty")).isEqualByComparingTo("7");
        assertThat((BigDecimal) lines.get(1).get("value")).isEqualByComparingTo("668.50");
        assertThat(lines.get(1).get("owner_entity_id")).isEqualTo(buyer);
        assertThat(lines.get(1).get("seller_entity_id")).isEqualTo(seller);
    }

    /**
     * Wave 2, M8-03: a second dispute after a resolution is a row of its own (the table is keyed
     * by the event), and the exception queue takes the latest: the invoice is disputed again.
     */
    @Test
    void aSecondDisputeAfterAResolutionIsKeptAndTheQueueShowsTheInvoiceDisputed() {
        UUID seller = Ids.next();
        UUID buyer = Ids.next();
        UUID invoice = Ids.next();
        Instant t = Instant.parse("2026-09-27T04:00:00Z");
        List<Delivery> flow = List.of(
                harness.event(InvoiceIssued.TYPE, seller, t, invoice(invoice, seller, buyer, null, "100.00")),
                harness.event(
                        InvoiceDisputed.TYPE,
                        buyer,
                        t.plusSeconds(60),
                        new InvoiceDisputed(invoice, seller, buyer, "PRICE")),
                harness.event(
                        InvoiceDisputeResolved.TYPE,
                        seller,
                        t.plusSeconds(120),
                        new InvoiceDisputeResolved(invoice, seller, buyer, seller)),
                harness.event(
                        InvoiceDisputed.TYPE,
                        buyer,
                        t.plusSeconds(180),
                        new InvoiceDisputed(invoice, seller, buyer, "QUANTITY")));
        for (Delivery event : flow) {
            harness.deliver(event, projection::on);
            harness.deliver(event, projection::on);
        }

        assertThat(admin.queryForList(
                        "select event_kind from reporting.trade_document_event order by occurred_at", String.class))
                .containsExactly("ISSUED", "DISPUTED", "DISPUTE_RESOLVED", "DISPUTED");
        assertThat(queries.exceptions(ScopeContext.dev(Ids.next(), seller, null)))
                .filteredOn(item -> item.kind().equals("INVOICE_DISPUTED"))
                .singleElement()
                .satisfies(item -> assertThat(item.since()).isEqualTo(t.plusSeconds(180)));
    }

    /**
     * Wave 2, RLS-08 and M8-07 (decision D4): a row carries its owner's location where the event
     * names it, and a shop session reads the rows of its own shop and nothing entity-wide, as it
     * reads kernel.document; the counterparty reads the rows it is party to entity-wide, from a
     * location-scoped PARTY session too (the template's party_read).
     */
    @Test
    void aShopSessionReadsTheTradingRowsOfItsShopOnly() {
        UUID supplier = Ids.next();
        UUID society = Ids.next();
        UUID customer = Ids.next();
        UUID shop1 = Ids.next();
        UUID shop2 = Ids.next();
        UUID customerShop = Ids.next();
        UUID sku = Ids.next();
        Instant t = Instant.parse("2026-09-27T04:00:00Z");
        LocalDate day = LocalDate.of(2026, 9, 27);
        UUID grnAtShop1 = Ids.next();
        harness.deliver(
                harness.event(GrnConfirmed.TYPE, society, t, grn(grnAtShop1, society, shop1, supplier, sku)),
                projection::on);
        harness.deliver(
                harness.event(GrnConfirmed.TYPE, society, t, grn(Ids.next(), society, shop2, supplier, Ids.next())),
                projection::on);
        // The society sells too: its invoice is entity-wide work, with no location.
        harness.deliver(
                harness.event(InvoiceIssued.TYPE, society, t, invoice(Ids.next(), society, customer, null, "50.00")),
                projection::on);

        assertThat(admin.queryForObject(
                        "select location_id from reporting.trade_document_event where document_id = ?",
                        UUID.class,
                        grnAtShop1))
                .isEqualTo(shop1);
        assertThat(admin.queryForObject(
                        "select location_id from reporting.trade_line_fact where document_id = ?",
                        UUID.class,
                        grnAtShop1))
                .isEqualTo(shop1);

        ReportParameters period = new ReportParameters(day, day, null);
        ScopeContext wide = ScopeContext.dev(Ids.next(), society, null);
        ScopeContext atShop1 = ScopeContext.dev(Ids.next(), society, shop1);
        assertThat(queries.report("trade-by-distributor", period, wide).rows()).hasSize(2);
        assertThat(queries.report("invoices-issued", period, wide).rows()).hasSize(1);
        // The shop reads its own GRN's lines, not the sibling shop's, and not the entity's invoice.
        assertThat(queries.report("trade-by-distributor", period, atShop1).rows())
                .hasSize(1);
        assertThat(queries.report("invoices-issued", period, atShop1).rows()).isEmpty();

        // The customer, from a session at one of its shops, reads the invoice it is the buyer of.
        ScopeContext customerAtItsShop = ScopeContext.dev(Ids.next(), customer, customerShop);
        ScopeContext party = new ScopeContext(
                customerAtItsShop.userId(),
                null,
                customer,
                customerAtItsShop.scopes(),
                customerAtItsShop.activeScope(),
                PolicyClass.PARTY,
                Set.of(),
                null,
                Locale.ENGLISH,
                Ids.next());
        assertThat(queries.report("invoices-issued", period, customerAtItsShop).rows())
                .hasSize(1);
        assertThat(queries.report("invoices-issued", period, party).rows()).hasSize(1);
    }

    /**
     * The trap the event key opens (wave 2, M8-03): a kind that repeats must not be added up twice.
     * Over random flows, with a second dispute and resolution on some invoices, the dashboard's
     * money tiles of each party equal the sums of the underlying facts, worked out here from the
     * events themselves: sales and purchases are the issued invoices' net in the last week, owed
     * and owing are each invoice's gross less what settled and credited it, never below zero.
     */
    @Test
    void theDashboardsMoneyTilesEqualTheSumsOfTheFactsWhenKindsRepeat() {
        Random random = new Random(42);
        UUID seller = Ids.next();
        UUID buyer = Ids.next();
        UUID[] skus = {Ids.next(), Ids.next()};
        // Inside the last week, whatever the day the test runs: the tiles' value is the week to today.
        Instant t = Instant.now().minusSeconds(3 * 86_400);
        List<Delivery> events = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            events.addAll(TradeFlows.flow(
                    harness, seller, buyer, skus, random, t.plusSeconds(i * 900L), 3 + random.nextInt(2)));
        }
        List<Delivery> repeats = new ArrayList<>();
        for (Delivery event : events) {
            if (event.envelope().path("eventType").asText().equals(InvoiceDisputeResolved.TYPE)) {
                UUID invoice = UUID.fromString(
                        event.envelope().path("payload").path("invoiceId").asText());
                Instant at = Instant.parse(event.envelope().path("occurredAt").asText());
                repeats.add(harness.event(
                        InvoiceDisputed.TYPE,
                        buyer,
                        at.plusSeconds(60),
                        new InvoiceDisputed(invoice, seller, buyer, "QUANTITY")));
                repeats.add(harness.event(
                        InvoiceDisputeResolved.TYPE,
                        seller,
                        at.plusSeconds(120),
                        new InvoiceDisputeResolved(invoice, seller, buyer, seller)));
            }
        }
        events.addAll(repeats);
        assertThat(repeats).as("the flows reached a resolved dispute").isNotEmpty();
        // Each delivered twice: a redelivery adds nothing either.
        for (Delivery event : events) {
            harness.deliver(event, projection::on);
            harness.deliver(event, projection::on);
        }

        // The facts, from the events: each invoice's net and gross, and what settled or credited it.
        Map<UUID, BigDecimal[]> invoices = new java.util.HashMap<>();
        Map<UUID, BigDecimal> settled = new java.util.HashMap<>();
        for (Delivery event : events) {
            var payload = event.envelope().path("payload");
            switch (event.envelope().path("eventType").asText()) {
                case InvoiceIssued.TYPE ->
                    invoices.put(UUID.fromString(payload.path("invoiceId").asText()), new BigDecimal[] {
                        new BigDecimal(payload.path("netAmount").asText()),
                        new BigDecimal(payload.path("grossAmount").asText())
                    });
                case "payment_receipt.recorded.v1" ->
                    payload.path("settlements")
                            .forEach(s -> settled.merge(
                                    UUID.fromString(s.path("invoiceId").asText()),
                                    new BigDecimal(s.path("amount").asText()),
                                    BigDecimal::add));
                case "payment_receipt.reversed.v1" ->
                    payload.path("reopened")
                            .forEach(s -> settled.merge(
                                    UUID.fromString(s.path("invoiceId").asText()),
                                    new BigDecimal(s.path("amount").asText()).negate(),
                                    BigDecimal::add));
                case "credit_note.issued.v1" ->
                    settled.merge(
                            UUID.fromString(payload.path("invoiceId").asText()),
                            new BigDecimal(payload.path("grossAmount").asText()),
                            BigDecimal::add);
                default -> {}
            }
        }
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal[]> invoice : invoices.entrySet()) {
            net = net.add(invoice.getValue()[0]);
            outstanding = outstanding.add(invoice.getValue()[1]
                    .subtract(settled.getOrDefault(invoice.getKey(), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO));
        }

        Dashboard sellers = queries.dashboard(ScopeContext.dev(Ids.next(), seller, null));
        Dashboard buyers = queries.dashboard(ScopeContext.dev(Ids.next(), buyer, null));
        assertThat(tile(sellers, "sales")).isEqualByComparingTo(net);
        assertThat(tile(buyers, "purchases")).isEqualByComparingTo(net);
        assertThat(tile(sellers, "receivables")).isEqualByComparingTo(outstanding);
        assertThat(tile(buyers, "payables")).isEqualByComparingTo(outstanding);
    }

    private static BigDecimal tile(Dashboard dashboard, String tileId) {
        return dashboard.tiles().stream()
                .filter(tile -> tile.tileId().equals(tileId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tile " + tileId))
                .value();
    }

    private static InvoiceIssued invoice(UUID invoice, UUID seller, UUID buyer, UUID grn, String net) {
        LocalDate day = LocalDate.of(2026, 9, 27);
        return new InvoiceIssued(
                invoice,
                "INV-" + invoice,
                Ids.next(),
                seller,
                buyer,
                null,
                null,
                grn == null ? List.of() : List.of(grn),
                day,
                day.plusDays(30),
                new BigDecimal(net),
                BigDecimal.ZERO,
                new BigDecimal(net),
                "hash");
    }

    private static GrnConfirmed grn(UUID grn, UUID receiver, UUID location, UUID seller, UUID sku) {
        return new GrnConfirmed(
                grn,
                "GRN-" + grn,
                receiver,
                location,
                seller,
                Ids.next(),
                Ids.next(),
                Ids.next(),
                null,
                Instant.parse("2026-09-27T04:00:00Z"),
                false,
                List.of(new GrnLineConfirmed(
                        Ids.next(),
                        1,
                        sku,
                        Ids.next(),
                        "B1",
                        LocalDate.of(2027, 1, 1),
                        new BigDecimal("120.00"),
                        "EA",
                        new BigDecimal("5"),
                        new BigDecimal("5"),
                        BigDecimal.ZERO,
                        new BigDecimal("10.0000"))));
    }

    @Test
    void anEventInAScopeThatIsNotItsOwnersIsRefused() {
        UUID seller = Ids.next();
        UUID buyer = Ids.next();
        Delivery rejected = harness.event(
                OrderRejected.TYPE,
                seller,
                Instant.parse("2026-09-27T04:00:00Z"),
                new OrderRejected(Ids.next(), Ids.next(), buyer, seller, "NO_STOCK"));
        Delivery cancelled = harness.event(
                OrderCancelled.TYPE,
                buyer,
                Instant.parse("2026-09-27T04:00:00Z"),
                new OrderCancelled(Ids.next(), Ids.next(), buyer, seller, buyer, "CHANGED_MIND"));
        harness.deliver(rejected, projection::on);
        harness.deliver(cancelled, projection::on);
        assertThat(admin.queryForList("select event_kind from reporting.trade_document_event order by 1", String.class))
                .containsExactly("CANCELLED", "REJECTED");

        // The owner of a row is the scope's entity: a scope with no entity writes nothing.
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> projection.on(
                        rejected.envelope(),
                        new lk.coopfed.knoweb.kernel.api.ScopeContext(
                                null, null, null, List.of(), null, null, null, null, null, null)));
    }
}
