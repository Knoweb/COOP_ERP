package lk.coopfed.knoweb.m8reporting.internal.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.m5inventory.api.StockMoved;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness;
import lk.coopfed.knoweb.m8reporting.ProjectionHarness.Delivery;
import lk.coopfed.knoweb.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M8-01's done criterion, "the harness runs on a sample projection", on the stock position
 * (28A section 9: rebuild equivalence first, then idempotency), with the payloads M5's
 * {@code StockMoved} record publishes.
 */
class StockPositionProjectionPostgresIntegrationTest extends PostgresIntegrationTest {

    private static final List<String> TABLES = List.of("reporting.projection_state", "reporting.stock_position");

    @Autowired
    StockPositionProjection projection;

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
    void aRebuildFromZeroGivesTheRowsTheLiveConsumerGaveForRandomStreams() {
        for (long seed = 1; seed <= 5; seed++) {
            List<Delivery> events = randomStream(new Random(seed), 60);

            ProjectionHarness.Result result = harness.liveThenRebuild(events, projection::on, TABLES, seed);

            assertThat(result.live().get("reporting.stock_position")).isNotEmpty();
            assertThat(result.rebuilt()).as("seed %d", seed).isEqualTo(result.live());
        }
        // A projection copies facts; it records and publishes nothing of its own.
        assertThat(kernel.committedAudit()).isEmpty();
        assertThat(kernel.committedEvents()).isEmpty();
    }

    @Test
    void theRowIsTheLotAsTheLastMovementLeftItAndAnOlderMovementChangesNothing() {
        UUID owner = Ids.next();
        UUID location = Ids.next();
        UUID batch = Ids.next();
        UUID sku = Ids.next();
        Instant t = Instant.parse("2026-09-27T04:00:00Z");
        Delivery receipt = moved(owner, location, batch, sku, "RECEIPT", "10", "10", "90.0000", 1, t);
        Delivery dispatch =
                moved(owner, location, batch, sku, "TRANSFER_OUT", "-4", "6", "90.0000", 2, t.plusSeconds(60));

        harness.deliver(receipt, projection::on);
        harness.deliver(dispatch, projection::on);
        harness.deliver(receipt, projection::on); // redelivered late: the newer figure stays
        harness.deliver(dispatch, projection::on); // twice: no change

        Map<String, Object> row = admin.queryForMap("select * from reporting.stock_position");
        assertThat(row.get("owner_entity_id")).isEqualTo(owner);
        assertThat(row.get("canonical_sku_id")).isEqualTo(sku);
        assertThat((BigDecimal) row.get("qty_on_hand")).isEqualByComparingTo("6");
        assertThat((BigDecimal) row.get("unit_cost")).isEqualByComparingTo("90");
        assertThat(row.get("last_movement_seq")).isEqualTo(2L);

        Map<String, Object> state = admin.queryForMap("select * from reporting.projection_state");
        assertThat(state.get("name")).isEqualTo("stock_position");
        assertThat(state.get("consumer")).isEqualTo("m8.stock_position");
        assertThat(state.get("owner_entity_id")).isEqualTo(owner);
        assertThat(state.get("last_event_type")).isEqualTo("stock.moved.v1");
        assertThat(state.get("last_event_id").toString())
                .isEqualTo(dispatch.envelope().path("eventId").asText());
    }

    @Test
    void anEventOfAnotherTypeIsIgnored() {
        UUID owner = Ids.next();
        harness.deliver(
                harness.event("order.submitted.v1", owner, Instant.parse("2026-09-27T04:00:00Z"), Map.of("x", 1)),
                projection::on);

        assertThat(admin.queryForObject("select count(*) from reporting.stock_position", Integer.class))
                .isZero();
        assertThat(admin.queryForObject("select count(*) from reporting.projection_state", Integer.class))
                .isZero();
    }

    @Test
    void aShopScopedDeliveryWritesAtItsShopAndRowLevelSecurityKeepsOtherEntitiesOut() {
        UUID owner = Ids.next();
        UUID other = Ids.next();
        UUID shop = Ids.next();
        Instant t = Instant.parse("2026-09-27T04:00:00Z");
        harness.deliver(moved(owner, shop, Ids.next(), Ids.next(), "RECEIPT", "5", "5", "10", 1, t), projection::on);

        // The consumer wrote as the application user, under row-level security, in the owner's
        // scope at the shop: a scope of another entity reads nothing of it.
        assertThat(admin.queryForObject("select count(*) from reporting.stock_position", Integer.class))
                .isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> projection.on(
                        moved(owner, shop, Ids.next(), Ids.next(), "RECEIPT", "5", "5", "10", 1, t)
                                .envelope(),
                        ProjectionHarness.system(other, shop)));
    }

    // ---- the event streams --------------------------------------------------------------------

    /**
     * A random stream of movements over two entities, three locations each, a few batches and
     * both conditions, each movement reporting its lot's quantity after it and numbered per
     * location and source as M5 numbers them; a till is a second source at one location.
     */
    private List<Delivery> randomStream(Random random, int size) {
        UUID[] owners = {Ids.next(), Ids.next()};
        Map<UUID, UUID[]> locations = new HashMap<>();
        for (UUID owner : owners) {
            locations.put(owner, new UUID[] {Ids.next(), Ids.next(), Ids.next()});
        }
        UUID[] batches = {Ids.next(), Ids.next(), Ids.next()};
        UUID[] skus = {Ids.next(), Ids.next(), Ids.next()};
        String[] conditions = {"GOOD", "DAMAGED"};
        String till = Ids.next().toString();

        Map<String, BigDecimal> lots = new HashMap<>();
        Map<String, Long> sequences = new HashMap<>();
        List<Delivery> events = new ArrayList<>();
        Instant t = Instant.parse("2026-09-01T02:00:00Z");

        for (int i = 0; i < size; i++) {
            UUID owner = owners[random.nextInt(owners.length)];
            UUID location = locations.get(owner)[random.nextInt(3)];
            int b = random.nextInt(batches.length);
            String condition = conditions[random.nextInt(2)];
            String source = random.nextInt(4) == 0 ? till : "central";
            BigDecimal delta = BigDecimal.valueOf(random.nextInt(21) - 8);
            if (delta.signum() == 0) {
                delta = BigDecimal.ONE;
            }
            String lot = location + "/" + batches[b] + "/" + condition;
            BigDecimal qty = lots.getOrDefault(lot, BigDecimal.ZERO).add(delta);
            lots.put(lot, qty);
            long seq = sequences.merge(location + "/" + source, 1L, Long::sum);
            t = t.plusSeconds(1 + random.nextInt(600));
            events.add(harness.event(
                    StockMoved.TYPE,
                    owner,
                    t,
                    new StockMoved(
                            Ids.next(),
                            owner,
                            location,
                            Ids.next(),
                            batches[b],
                            skus[b],
                            condition,
                            delta.signum() > 0 ? "RECEIPT" : "SALE",
                            delta,
                            new BigDecimal(100 + random.nextInt(50) + ".2500"),
                            qty,
                            Ids.next(),
                            source,
                            seq)));
        }
        return events;
    }

    private Delivery moved(
            UUID owner,
            UUID location,
            UUID batch,
            UUID sku,
            String type,
            String delta,
            String lotQty,
            String cost,
            long seq,
            Instant at) {
        return harness.event(
                StockMoved.TYPE,
                owner,
                at,
                new StockMoved(
                        Ids.next(),
                        owner,
                        location,
                        Ids.next(),
                        batch,
                        sku,
                        "GOOD",
                        type,
                        new BigDecimal(delta),
                        new BigDecimal(cost),
                        new BigDecimal(lotQty),
                        Ids.next(),
                        "central",
                        seq));
    }
}
