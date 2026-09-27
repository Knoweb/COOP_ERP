package lk.coopfed.knoweb.m8reporting.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The trading projections (M8-06 in part) against the payloads of M4's event records: rebuild
 * equivalence over random trading flows first (28A section 9), then what one flow leaves.
 */
class TradeProjectionPostgresIntegrationTest extends PostgresIntegrationTest {

    static final List<String> TABLES =
            List.of("reporting.projection_state", "reporting.trade_document_event", "reporting.trade_line_fact");

    @Autowired
    TradeProjection projection;

    @Autowired
    ObjectMapper mapper;

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
                        harness, seller, buyers[random.nextInt(3)], skus, random, t, random.nextInt(4)));
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
